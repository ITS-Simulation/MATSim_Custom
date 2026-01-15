package com.thomas.pt.data.scoring

import com.thomas.pt.data.db.DuckDBManager
import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.utility.Utility
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path
import kotlin.math.exp
import kotlin.system.exitProcess
import kotlin.time.measureTimedValue

class BusNetScoreCalculator(configPath: Path) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val db = DuckDBManager()

    private val busPassengerRecordsPath: String
    private val busDelayRecordsPath: String
    private val tripRecordsPath: String

    private val metadata by lazy { MATSimMetadataStore.metadata }

    private val scoringWeights: ScoringWeights

    init {
        val yamlConfig = Utility.loadYaml(configPath)
        Utility.getYamlSubconfig(yamlConfig, "files", "data").let {
            busPassengerRecordsPath = it["bus_pax_records"] as String
            busDelayRecordsPath = it["bus_delay_records"] as String
            tripRecordsPath = it["trip_records"] as String
        }
        Utility.getYamlSubconfig(yamlConfig, "scoring", "weights").let {
            scoringWeights = ScoringWeights(
                serviceCoverage = it["service_coverage"] as Double,
                ridership = it["ridership"] as Double,
                travelTime = it["travel_time"] as Double,
                transitAutoTimeRatio = it["transit_auto_time_ratio"] as Double,
                onTimePerf = it["on_time_performance"] as Double,
                productivity = it["productivity"] as Double,
            )
        }
    }

    override fun close() = db.close()

    private fun calculateRidership(): Double = db.queryScalar(
        "SELECT COUNT(DISTINCT person_id) FROM read_arrow('$busPassengerRecordsPath')"
    ) / metadata.totalPopulation

    private fun calculateOnTimePerf(): Double = db.queryScalar(
        """
            WITH on_time_data AS (
                SELECT * FROM read_arrow('$busDelayRecordsPath')
            )
            SELECT
                COUNT_IF(
                    arrival_delay >= ${-60 * metadata.earlyHeadwayTolerance}
                    AND arrival_delay <= ${60 * metadata.lateHeadwayTolerance}
                ) * 1.0 / COUNT(*) AS on_time_ratio
            FROM on_time_data
        """
    )

    private fun calculateTravelTimeRatio(): Double = exp(
        -db.queryScalar(
            """
                SELECT
                    (COALESCE(AVG(travel_time) FILTER (WHERE main_mode = 'pt'), 1e9) /
                    COALESCE(AVG(travel_time) FILTER (WHERE main_mode = 'car'), 1.0)) AS tt_ratio
                FROM read_arrow('$tripRecordsPath')
                WHERE main_mode IN ('pt', 'car')
            """
        )
    )

    private fun calculateTravelTime(): Double = exp(
        -db.queryScalar(
            """
                SELECT
                    (COALESCE(AVG(travel_time), 1e9) / ${60.0 * metadata.travelTimeBaseline}) AS travel_time_score
                FROM read_arrow('$tripRecordsPath')
                WHERE main_mode = 'pt'
            """
        )
    )

    private fun calculateProductivity(): Double = exp(
        -db.queryScalar(
            """
                SELECT 
                    COALESCE(${metadata.totalServiceHours} / NULLIF(COUNT(DISTINCT person_id), 0), 1e9)
                FROM read_arrow('$busPassengerRecordsPath')
            """.trimIndent()
        )
    )

    fun calculateScore(out: File) {
        val (score, time) = measureTimedValue {
            logger.info("Processing MATSim event data...")

            logger.info("Calculating service coverage...")
            val serviceCoverage = metadata.serviceCoverage
            logger.info("Calculated service coverage: {}%", "%.4f".format(serviceCoverage * 100))

            logger.info("Calculating ridership...")
            val ridership = try { calculateRidership() } catch (e: Exception) {
                logger.error("Error calculating ridership: {}", e.message)
                exitProcess(1)
            }
            logger.info("Calculated ridership: {}%", "%.4f".format(ridership * 100))

            logger.info("Calculating on-time performance...")
            val onTimePerf = try { calculateOnTimePerf() } catch (e: Exception) {
                logger.error("Error calculating on-time performance: {}", e.message)
                exitProcess(1)
            }
            logger.info("Calculated on-time performance: {}%", "%.4f".format(onTimePerf * 100))

            logger.info("Calculating transit-auto travel time ratio...")
            val travelTimeRatio = try { calculateTravelTimeRatio() } catch (e: Exception) {
                logger.error("Error calculating travel time ratio: {}", e.message)
                exitProcess(1)
            }
            logger.info("Calculated travel time ratio: {}", "%.4f".format(travelTimeRatio))

            logger.info("Calculating travel time...")
            val travelTime = try { calculateTravelTime() } catch (e: Exception) {
                logger.error("Error calculating travel time: {}", e.message)
                exitProcess(1)
            }
            logger.info("Calculated travel time score component: {}", "%.4f".format(travelTime))

            logger.info("Calculating productivity...")
            val productivity = try { calculateProductivity() } catch (e: Exception) {
                logger.error("Error calculating productivity: {}", e.message)
                exitProcess(1)
            }
            logger.info("Calculated productivity score component: {}", "%.4f".format(productivity))

            scoringWeights.serviceCoverage * serviceCoverage +
                    scoringWeights.ridership * ridership +
                    scoringWeights.onTimePerf * onTimePerf +
                    scoringWeights.travelTime * travelTime +
                    scoringWeights.transitAutoTimeRatio * travelTimeRatio +
                    scoringWeights.productivity * productivity
        }
        logger.info("MATSim data processing & Score calculation completed in $time")
        logger.info("System-wide score: %.4f".format(score))
        DataOutputStream(out.outputStream()).use { it.writeDouble(score) }
    }
}
