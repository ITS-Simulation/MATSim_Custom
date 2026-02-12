package com.thomas.pt.scoring

import com.thomas.pt.db.DuckDBManager
import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.utils.Utility
import com.thomas.pt.writer.core.WriterFormat
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path
import kotlin.math.exp
import kotlin.system.exitProcess
import kotlin.time.measureTimedValue

class BusNetScoreCalculator(
    configPath: Path,
    format: WriterFormat
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val db = DuckDBManager()

    private val busPassengerRecords: String
    private val busDelayRecords: String
    private val busTripRecords: String
    private val tripRecords: String

    private val metadata by lazy { MATSimMetadataStore.metadata }

    private val scoringWeights: ScoringWeights

    init {
        Utility.loadYaml(configPath).let { cfg ->
            Utility.getYamlSubconfig(cfg, "files", "data").let {
                busPassengerRecords = format.resolveExtension(
                    it["bus_pax_records"] as String
                ).let { file ->
                    when (format) {
                        WriterFormat.ARROW -> "read_arrow('$file')"
                        WriterFormat.CSV -> """
                        read_csv('$file', 
                            header=true, 
                            columns={
                                person_id: VARCHAR,
                                bus_id: VARCHAR,
                            }
                        )
                        """.trimIndent()
                    }
                }
                busDelayRecords = format.resolveExtension(
                    it["bus_delay_records"] as String
                ).let { file ->
                    when (format) {
                        WriterFormat.ARROW -> "read_arrow('$file')"
                        WriterFormat.CSV -> """
                        read_csv('$file', 
                            header=true, 
                            columns={
                                stop_id: VARCHAR,
                                arrival_delay: DOUBLE,
                                depart_delay: DOUBLE
                            }
                        )
                        """.trimIndent()
                    }

                }
                busTripRecords = format.resolveExtension(
                    it["bus_trip_records"] as String
                ).let { file ->
                    when (format) {
                        WriterFormat.ARROW -> "read_arrow('$file')"
                        WriterFormat.CSV -> """
                        read_csv('$file', 
                            header=true, 
                            columns={
                                bus_id: VARCHAR,
                                link_id: VARCHAR,
                                link_length: DOUBLE,
                                travel_time: DOUBLE,
                                have_passenger: BOOLEAN,
                            }
                        )
                        """.trimIndent()
                    }
                }
                tripRecords = format.resolveExtension(
                    it["trip_records"] as String
                ).let { file ->
                    when (format) {
                        WriterFormat.ARROW -> "read_arrow('$file')"
                        WriterFormat.CSV -> """
                        read_csv('$file', 
                            header=true, 
                            columns={
                                person_id: VARCHAR,
                                start_time: DOUBLE,
                                travel_time: DOUBLE,
                                main_mode: VARCHAR,
                                veh_list: 'VARCHAR[]'
                            }
                        )
                        """.trimIndent()
                    }
                }
            }
            scoringWeights = ScoringWeights.fromYaml(
                Utility.getYamlSubconfig(cfg, "scoring", "weights")
            )
        }

    }

    override fun close() = db.close()

    private fun isDataSourceEmpty(dataSource: String): Boolean 
        = db.queryScalar("SELECT COUNT(*) FROM $dataSource") == 0.0

    private fun calculateRidership(): Double = db.queryScalar(
        "SELECT COUNT(DISTINCT person_id) FROM $busPassengerRecords"
    ) / metadata.totalPopulation

    private fun calculateTransfersRate(): Double {
        val busList = metadata.bus.joinToString(", ") { "'${it.toString().replace("'", "''")}'" }
        return db.queryScalar(
            """
                WITH pt_trips AS (
                    SELECT veh_list FROM $tripRecords WHERE main_mode = 'pt'
                ),
                trip_count AS (
                    SELECT COUNT(*) AS total FROM pt_trips
                ),
                transfer_check AS (
                    SELECT list_bool_or(
                        [
                            veh_list[i] IN ($busList) AND veh_list[i+1] IN ($busList)
                            FOR i IN range(1, len(veh_list))
                        ]
                    ) AS has_transfer
                    FROM pt_trips
                    WHERE len(veh_list) >= 2
                )
                SELECT
                    1.0 - COUNT_IF(has_transfer) * 1.0 / NULLIF((SELECT COUNT(*) FROM pt_trips), 0) AS transfer_rate
                FROM transfer_check
            """.trimIndent()
        )
    }

    @Suppress("unused")
    private fun calculateBusRevenueHours(): Double = db.queryScalar(
        """
            SELECT 
                COALESCE(SUM(travel_time) / 3600.0, 0.0) AS service_hours
            FROM $busTripRecords
            WHERE have_passenger
        """.trimIndent()
    )

    private fun calculateOnTimePerf(): Double = db.queryScalar(
        """
            WITH on_time_data AS (
                SELECT * FROM $busDelayRecords
            )
            SELECT
                COUNT_IF(
                    arrival_delay >= ${-60 * metadata.earlyHeadwayTolerance}
                    AND arrival_delay <= ${60 * metadata.lateHeadwayTolerance}
                ) * 1.0 / COUNT(arrival_delay) AS on_time_ratio
            FROM on_time_data
        """.trimIndent()
    )

    private fun calculateTravelTimeRatio(): Double = exp(
        -db.queryScalar(
            """
                SELECT
                    (COALESCE(AVG(travel_time) FILTER (WHERE main_mode = 'pt'), 1e9) /
                    COALESCE(AVG(travel_time) FILTER (WHERE main_mode = 'car'), 1.0)) AS tt_ratio
                FROM $tripRecords
                WHERE main_mode IN ('pt', 'car')
            """.trimIndent()
        )
    )

    private fun calculateTravelTime(): Double = exp(
        -db.queryScalar(
            """
                SELECT
                    (COALESCE(AVG(travel_time), 1e9) / ${60.0 * metadata.travelTimeBaseline}) AS travel_time_score
                FROM $tripRecords
                WHERE main_mode = 'pt'
            """.trimIndent()
        )
    )

    private fun calculateProductivity(): Double = exp(
        -metadata.productivityBaseline * db.queryScalar(
            """
                WITH total_service_hours AS (
                    SELECT 
                        COALESCE(SUM(travel_time) / 3600.0, 0.0) AS service_hours
                    FROM $busTripRecords
                ),
                total_passenger AS (
                    SELECT 
                        COUNT(DISTINCT person_id) AS passenger_count
                    FROM $busPassengerRecords
                )
                SELECT
                    (SELECT service_hours FROM total_service_hours) /
                    NULLIF((SELECT passenger_count FROM total_passenger), 0) AS productivity_ratio
            """.trimIndent()
        )
    )
    
    private fun calculateBusEfficiency(): Double = exp(
        -db.queryScalar(
            """
                SELECT 
                    COALESCE(SUM(link_length), 1e9) / NULLIF((SELECT COUNT(DISTINCT person_id) FROM $busPassengerRecords), 0)
                FROM $busTripRecords
            """.trimIndent()
        )
    )

    private fun calculateBusEffectiveTravelDist(): Double = db.queryScalar(
        """
            WITH bus_trip_data AS (
                SELECT * FROM $busTripRecords
            )
            SELECT (
                SUM(
                    CASE WHEN have_passenger THEN link_length ELSE 0.0 END
                ) / NULLIF(SUM(link_length), 0.0)
            ) AS effective_travel_distance_ratio
            FROM bus_trip_data
            WHERE NOT isnan(link_length) AND NOT isinf(link_length)
        """.trimIndent()
    )

    private inline fun computeScore(
        weight: Double,
        scoreName: String,
        calculator: () -> Double,
        logOriginal: Boolean,
        vararg requiredDataSources: String
    ): Double =
        if (weight > 0 || weight == Double.NEGATIVE_INFINITY) {
            val emptyDataSources = requiredDataSources.filter { isDataSourceEmpty(it) }
            if (emptyDataSources.isNotEmpty()) {
                logger.warn("Skipping {} calculation: no events recorded", scoreName)
                0.0
            } else {
                logger.info("Calculating {}...", scoreName)
                val score = try { calculator() } catch (e: Exception) {
                    logger.error("Error calculating {}\n{}", scoreName, e.message)
                    exitProcess(1)
                }
                if (score.isFinite()) {
                    if (logOriginal) logger.info("Calculated {} score: {}", scoreName, "%.4f".format(score))
                    else logger.info("Calculated {}: {}%", scoreName, "%.4f".format(score * 100))
                    score
                } else {
                    logger.warn("Invalid {} score (non-finite value)", scoreName)
                    0.0
                }
            }
        } else 0.0

    fun calculateScore(out: File, scoreRecords: File? = null) {
        val (score, time) = measureTimedValue {
            logger.info("Processing MATSim event data...")

            logger.info("Calculating service coverage...")
            val serviceCoverage = metadata.serviceCoverage
            logger.info("Calculated service coverage: {}%", "%.4f".format(serviceCoverage * 100))

            logger.info("Calculating transit route ratio...")
            val transitRouteRatio = metadata.transitRouteRatios
            logger.info("Calculated transit route ratio: {}%", "%.4f".format(transitRouteRatio * 100))

            val ridership = computeScore(
                if (scoreRecords == null) scoringWeights.ridership else Double.NEGATIVE_INFINITY,
                "ridership", ::calculateRidership, false,
                busPassengerRecords
            )
            val onTimePerf = computeScore(
                if (scoreRecords == null) scoringWeights.onTimePerf else Double.NEGATIVE_INFINITY,
                "on-time performance", ::calculateOnTimePerf, false,
                busDelayRecords
            )
            val travelTimeRatio = computeScore(
                if (scoreRecords == null) scoringWeights.transitAutoTimeRatio else Double.NEGATIVE_INFINITY,
                "transit-auto travel time ratio", ::calculateTravelTimeRatio, true,
                tripRecords
            )
            val travelTime = computeScore(
                if (scoreRecords == null) scoringWeights.travelTime else Double.NEGATIVE_INFINITY,
                "travel time", ::calculateTravelTime, true,
                tripRecords
            )
            val productivity = computeScore(
                if (scoreRecords == null) scoringWeights.productivity else Double.NEGATIVE_INFINITY,
                "productivity", ::calculateProductivity, true,
                busTripRecords, busPassengerRecords
            )
            val busEfficiency = computeScore(
                if (scoreRecords == null) scoringWeights.busEfficiency else Double.NEGATIVE_INFINITY,
                "bus efficiency", ::calculateBusEfficiency, true,
                busTripRecords, busPassengerRecords
            )
            val busEffectiveTravelDist = computeScore(
                if (scoreRecords == null) scoringWeights.busEffectiveTravelDistance else Double.NEGATIVE_INFINITY,
                "bus effective travel distance rate", ::calculateBusEffectiveTravelDist, false,
                busTripRecords
            )
            val busTransferRate = computeScore(
                if (scoreRecords == null) scoringWeights.busTransferRate else Double.NEGATIVE_INFINITY,
                "bus transfer rate", ::calculateTransfersRate, true,
                tripRecords
            )

            val finalScore = scoringWeights.transitRouteRatio * transitRouteRatio +
                    scoringWeights.serviceCoverage * serviceCoverage +
                    scoringWeights.ridership * ridership +
                    scoringWeights.onTimePerf * onTimePerf +
                    scoringWeights.travelTime * travelTime +
                    scoringWeights.transitAutoTimeRatio * travelTimeRatio +
                    scoringWeights.productivity * productivity +
                    scoringWeights.busEfficiency * busEfficiency +
                    scoringWeights.busEffectiveTravelDistance * busEffectiveTravelDist +
                    scoringWeights.busTransferRate * busTransferRate

            scoreRecords?.let {
                assert(it.extension.lowercase() == "json") {
                    "Score records output file must be in JSON format."
                }
                logger.info("Writing score records...")
                ScoringRecords(
                    transitRouteRatio = transitRouteRatio,
                    serviceCoverage = serviceCoverage,
                    ridership = ridership,
                    travelTime = travelTime,
                    transitAutoTimeRatio = travelTimeRatio,
                    onTimePerf = onTimePerf,
                    productivity = productivity,
                    busEfficiency = busEfficiency,
                    busEffectiveTravelDistance = busEffectiveTravelDist,
                    busTransferRate = busTransferRate,
                    finalScore = finalScore,
                ).writeJson(it)
                logger.info("Finished writing score records.")
            }
            finalScore
        }
        logger.info("MATSim data processing & Score Calculation completed in $time")
        logger.info("System-wide score: %.4f".format(score))
        DataOutputStream(out.outputStream()).use { it.writeDouble(score) }
    }
}
