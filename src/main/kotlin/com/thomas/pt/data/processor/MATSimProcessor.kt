package com.thomas.pt.data.processor

import com.thomas.pt.data.db.DuckDBManager
import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.utility.Utility
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.writeArrowIPC
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.pt.transitSchedule.api.TransitLine
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.measureTime

class MATSimProcessor(configPath: Path) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val db = DuckDBManager()
    private val linkRecordsPath: String
    private val stopRecordsPath: String

    private val losOutput: File
    private val avgLenOutput: File

    private val metadata by lazy { MATSimMetadataStore.metadata }
    private val passengerThreshold: Int
    private val boardingThreshold: Int
    private val excludeLines: Set<String>
    private val lineFilter: String

    init {
        val yamlConfig = Utility.loadYaml(configPath)
        val dataOutConfig = Utility.getYamlSubconfig(yamlConfig, "files", "data")
        linkRecordsPath = dataOutConfig["link_records"] as String
        stopRecordsPath = dataOutConfig["stop_records"] as String
        losOutput = File(dataOutConfig["los_records"] as String).apply { absoluteFile.parentFile.mkdirs() }
        avgLenOutput = File(dataOutConfig["avg_trip_len"] as String).apply { absoluteFile.parentFile.mkdirs() }
        passengerThreshold = Utility.getYamlSubconfig(yamlConfig, "scoring", "wait_ride").let {
            it["total_load_threshold"] as Int
        }
        boardingThreshold = Utility.getYamlSubconfig(yamlConfig, "scoring", "wait_ride").let {
            it["boarding_threshold"] as Int
        }
        excludeLines = (Utility.getYamlSubconfig(yamlConfig, "processing")["exclude_lines"] as? List<*>)
            ?.map { it.toString() }
            ?.toSet() ?: emptySet()
        
        lineFilter = if (excludeLines.isNotEmpty()) {
            "AND line_id NOT IN (${excludeLines.joinToString { "'$it'" }})"
        } else ""
        
        if (excludeLines.isNotEmpty()) {
            logger.warn("Excluding lines from analysis: {}", excludeLines)
        }
    }

    override fun close() = db.close()

    private fun processAvgTripLength(): Double = db.queryScalar(
        """
        SELECT AVG(total_distance) FROM (
            SELECT vehicle_id, trip_id, SUM(travel_distance) as total_distance
            FROM (SELECT DISTINCT * FROM read_arrow('$linkRecordsPath'))
            WHERE travel_distance > 0
            GROUP BY vehicle_id, trip_id
        )
        """
    )

    private inner class ProcessAvgLoadFactor {
        fun process(): AnyFrame
            = db.query("""
                WITH bus_data AS (
                    SELECT DISTINCT link_id, line_id, passenger_load,
                           (exit_time - enter_time) as duration,
                           passenger_load::DOUBLE / ${metadata.busCap} as lf
                    FROM read_arrow('$linkRecordsPath')
                    WHERE is_bus = true $lineFilter
                    AND (exit_time - enter_time) >= 1 
                    AND travel_distance >= 0
                    AND (travel_distance / (exit_time - enter_time)) <= 42
                ),
                line_stats AS (
                    SELECT link_id, line_id,
                        SUM(passenger_load) as total_passengers,
                        SUM(lf * passenger_load * duration) as weighted_lf,
                        SUM(passenger_load * duration) as pax_seconds,
                        SUM(lf * duration) as time_weighted_lf,
                        SUM(duration) as total_duration
                    FROM bus_data
                    GROUP BY link_id, line_id
                ),
                line_lf AS (
                    SELECT link_id, line_id,
                        CASE WHEN total_passengers >= $passengerThreshold 
                             THEN weighted_lf / NULLIF(pax_seconds, 0)
                             ELSE time_weighted_lf / NULLIF(total_duration, 0)
                        END as avg_lf
                    FROM line_stats
                ),
                line_map AS (
                    SELECT link_id, MAP(LIST(line_id), LIST(avg_lf)) as line_avg_lf
                    FROM line_lf
                    GROUP BY link_id
                ),
                link_stats AS (
                    SELECT link_id,
                        SUM(total_passengers) as total_passengers,
                        SUM(weighted_lf) as weighted_lf,
                        SUM(pax_seconds) as pax_seconds,
                        SUM(time_weighted_lf) as time_weighted_lf,
                        SUM(total_duration) as total_duration
                    FROM line_stats
                    GROUP BY link_id
                ),
                link_lf AS (
                    SELECT link_id,
                        CASE WHEN total_passengers >= $passengerThreshold 
                             THEN weighted_lf / NULLIF(pax_seconds, 0)
                             ELSE time_weighted_lf / NULLIF(total_duration, 0)
                        END as avg_lf
                    FROM link_stats
                )
                SELECT lf.link_id, lf.avg_lf, lm.line_avg_lf, ls.pax_seconds, ls.total_duration
                FROM link_lf lf
                JOIN link_stats ls ON lf.link_id = ls.link_id
                LEFT JOIN line_map lm ON lf.link_id = lm.link_id
            """)
    }

    private inner class ProcessVehicleFlow {
        fun process(): AnyFrame
            = db.query("""
                WITH base AS (
                    SELECT DISTINCT *, (exit_time - enter_time) as duration
                    FROM read_arrow('$linkRecordsPath')
                    WHERE (exit_time - enter_time) >= 1 
                    AND travel_distance >= 0
                    AND (travel_distance / (exit_time - enter_time)) <= 42
                ),
                sliced AS (
                    SELECT 
                        link_id, line_id, is_bus, travel_distance, duration,
                        hour,
                        LEAST(exit_time, (hour + 1) * 3600.0) - 
                            GREATEST(enter_time, hour * 3600.0) as slice_duration
                    FROM base,
                    LATERAL (
                        SELECT UNNEST(GENERATE_SERIES(
                            CAST(FLOOR(enter_time / 3600) AS INTEGER),
                            CAST(FLOOR((exit_time - 0.001) / 3600) AS INTEGER)
                        )) as hour
                    )
                ),
                hourly AS (
                    SELECT link_id, hour,
                        SUM(travel_distance * slice_duration / duration) as hourly_distance,
                        SUM(slice_duration) as hourly_duration,
                        COUNT(*) as vehicle_count
                    FROM sliced
                    GROUP BY link_id, hour
                ),
                link_stats AS (
                    SELECT link_id,
                        AVG(vehicle_count) as veh_flow,
                        SUM(hourly_distance) / NULLIF(SUM(hourly_duration), 0) as avg_speed
                    FROM hourly
                    GROUP BY link_id
                ),
                bus_link_speed AS (
                    SELECT link_id,
                        SUM(travel_distance * slice_duration / duration) / 
                            NULLIF(SUM(slice_duration), 0) as bus_link_avg_speed
                    FROM sliced WHERE is_bus = true $lineFilter
                    GROUP BY link_id
                ),
                bus_line_speed AS (
                    SELECT link_id, line_id,
                        SUM(travel_distance * slice_duration / duration) / 
                            NULLIF(SUM(slice_duration), 0) as line_avg_speed
                    FROM sliced WHERE is_bus = true $lineFilter
                    GROUP BY link_id, line_id
                ),
                bus_line_map AS (
                    SELECT link_id, 
                        MAP(LIST(line_id), LIST(line_avg_speed)) as bus_line_avg_speed
                    FROM bus_line_speed
                    GROUP BY link_id
                )
                SELECT 
                    ls.link_id, ls.veh_flow, ls.avg_speed,
                    COALESCE(bls.bus_link_avg_speed, 0) as bus_link_avg_speed,
                    COALESCE(blm.bus_line_avg_speed, MAP {}) as bus_line_avg_speed
                FROM link_stats ls
                LEFT JOIN bus_link_speed bls ON ls.link_id = bls.link_id
                LEFT JOIN bus_line_map blm ON ls.link_id = blm.link_id
            """)
    }

    private inner class ProcessEWT {
        private fun getEWTAtStops(): Pair<AnyFrame, Map<String, Map<String, Double>>> {
            val dfEWT = db.query("""
                WITH weighted AS (
                    SELECT link_id, line_id, schedule_deviation, boarding,
                        schedule_deviation * boarding as weighted_deviation
                    FROM read_arrow('$stopRecordsPath') 
                    WHERE 1=1 $lineFilter
                ),
                line_stats AS (
                    SELECT link_id, line_id,
                        SUM(weighted_deviation) as sum_weighted,
                        SUM(boarding) as sum_weight,
                        AVG(schedule_deviation) as mean_delay
                    FROM weighted
                    GROUP BY link_id, line_id
                ),
                line_ewt AS (
                    SELECT link_id, line_id,
                        CASE WHEN sum_weight >= $boardingThreshold
                             THEN sum_weighted / sum_weight
                             ELSE mean_delay
                        END as ewt
                    FROM line_stats
                ),
                line_map AS (
                    SELECT link_id, MAP(LIST(line_id), LIST(ewt)) as line_ewt_map
                    FROM line_ewt
                    GROUP BY link_id
                ),
                link_stats AS (
                    SELECT link_id,
                        SUM(weighted_deviation) as sum_weighted,
                        SUM(boarding) as sum_weight,
                        AVG(schedule_deviation) as mean_delay
                    FROM weighted
                    GROUP BY link_id
                ),
                link_ewt AS (
                    SELECT link_id,
                        CASE WHEN sum_weight >= $boardingThreshold 
                             THEN sum_weighted / sum_weight
                             ELSE mean_delay
                        END as measured_ewt
                    FROM link_stats
                )
                SELECT le.link_id, le.measured_ewt, lm.line_ewt_map
                FROM link_ewt le
                LEFT JOIN line_map lm ON le.link_id = lm.link_id
            """)

            val ewtAtStops = dfEWT.rows().associate { row ->
                val linkId = row["link_id"] as String
                val rawMap = row["line_ewt_map"] as? Map<*, *> ?: emptyMap<String, Double>()
                val typedMap = rawMap.entries.associate { (k, v) ->
                    k.toString() to ((v as? Number)?.toDouble() ?: 0.0)
                }
                linkId to typedMap
            }

            return dfEWT.select("link_id", "measured_ewt") to ewtAtStops
        }

        private fun interpolateEWTForLine(
            ewtAtStops: Map<String, Map<String, Double>>
        ): AnyFrame {
            val acc = mutableMapOf<Pair<Id<Link>, Id<TransitLine>>, MutableList<Double>>()
            val linkLengths: Map<Id<Link>, Double> = metadata.linkData.mapValues {
                (_, linkMeta) -> linkMeta.length
            }
            
            
            metadata.busRoutes.forEach { (lineId, lineRoutes) ->
                // Skip excluded lines
                if (lineId.toString() in excludeLines) {
                    return@forEach
                }
                
                val resultAccumulator: (Id<Link>, Double) -> Unit = { linkId, ewt ->
                    acc.getOrPut(linkId to lineId) { mutableListOf() }.add(ewt)
                }

                for (route in lineRoutes) {
                    val routeLinks = route.route.map { it.toString() }
                    val stopsEWTIdx = route.route.mapIndexedNotNull { index, linkId ->
                        if (ewtAtStops["$linkId"]?.containsKey("$lineId") == true) index else null
                    }
                    if (stopsEWTIdx.isEmpty()) {
                        continue
                    }

                    val firstIdx = stopsEWTIdx.first()
                    val firstEWT = ewtAtStops[routeLinks[firstIdx]]!!["$lineId"]!!
                    (0..firstIdx).forEach { resultAccumulator(route.route[it], firstEWT) }

                    (0 until stopsEWTIdx.size - 1).forEach { i ->
                        val startIdx = stopsEWTIdx[i]
                        val endIdx = stopsEWTIdx[i + 1]
                        val startEWT = ewtAtStops[routeLinks[startIdx]]!!["$lineId"]!!
                        val endEWT = ewtAtStops[routeLinks[endIdx]]!!["$lineId"]!!
                        val segLen = (startIdx..endIdx).sumOf {
                            linkLengths[route.route[it]] ?: 0.0
                        }
                        var currLen = 0.0

                        (1 until (endIdx - startIdx)).forEach { k ->
                            val idx = startIdx + k
                            currLen += linkLengths[route.route[idx]] ?: 0.0
                            val ratio = (currLen / segLen).takeIf { segLen > 0 } ?: 1.0
                            val ewtInter = startEWT + (endEWT - startEWT) * ratio
                            resultAccumulator(route.route[idx], ewtInter)
                        }
                        resultAccumulator(route.route[endIdx], endEWT)
                    }

                    val lastIdx = stopsEWTIdx.last()
                    val lastEWT = ewtAtStops[routeLinks[lastIdx]]!!["$lineId"]!!
                    (lastIdx + 1 until route.route.size).forEach {
                        resultAccumulator(route.route[it], lastEWT)
                    }
                }
            }
            
            
            return acc.map { (key, values) ->
                mapOf(
                    "link_id" to key.first.toString(),
                    "line_id" to key.second.toString(),
                    "ewt" to values.average()
                )
            }.toDataFrame()
        }

        private fun interpolateEWTForLink(dfLineInterpolated: AnyFrame): AnyFrame = dfLineInterpolated
            .add("weight") {
                metadata.linesHeadway[Id.create(
                    "line_id"<String>(), TransitLine::class.java
                )]?.let { 3600.0 / it }
            }
            .add("weighted_ewt") { "ewt"<Double>() * "weight"<Double>() }
            .groupBy("link_id")
            .aggregate {
                sum("weighted_ewt") into "weighted_ewt_sum"
                sum("weight") into "weight_sum"
                mean("ewt") into "mean_ewt"
            }
            .add("interpolated_ewt") {
                if ("weight_sum"<Double>() > 0)
                    "weighted_ewt_sum"<Double>() / "weight_sum"<Double>()
                else "mean_ewt"()
            }
            .select("link_id", "interpolated_ewt")

        fun process(): AnyFrame {
            val (dfEWTPerLink, ewtAtStops) = getEWTAtStops()

            val dfEWTLine = interpolateEWTForLine(ewtAtStops)
            val dfEWTLinkInterpolated = interpolateEWTForLink(dfEWTLine)
            
            val dfEWTLink = dfEWTLinkInterpolated
                .leftJoin(dfEWTPerLink, "link_id")
                .add("ewt") {
                    @Suppress("RemoveExplicitTypeArguments")
                    "measured_ewt"<Double?>() ?: "interpolated_ewt"<Double>()
                }
                .select("link_id", "ewt")

            val busLineEWTMap = dfEWTLine.rows()
                .groupBy { it["link_id"] as String }
                .mapValues { (_, rows) ->
                    rows.associate { row ->
                        row["line_id"] as String to row["ewt"] as Double
                    }
                }

            return dfEWTLink
                .add("line_ewt") { busLineEWTMap["link_id"()] ?: emptyMap() }
        }
    }


    fun processAll() {
        val time = measureTime {
            logger.info("Starting Average Trip Length processing...")
            val avgTripLength: Double = try {
                processAvgTripLength()
            } catch (e: Exception) {
                logger.error("Failed to process Average Trip Length", e)
                exitProcess(1)
            }
            DataOutputStream(avgLenOutput.outputStream()).use {
                it.writeDouble(avgTripLength)
            }

            logger.info("Starting Average Load Factor processing...")
            val avgLoadFactorDF = try {
                ProcessAvgLoadFactor().process()
            } catch (e: Exception) {
                logger.error("Failed to process Average Load Factor", e)
                exitProcess(1)
            }
            
            logger.info("Starting Vehicle Flow processing...")
            val vehicleFlowDF = try {
                ProcessVehicleFlow().process()
            } catch (e: Exception) {
                logger.error("Failed to process Vehicle Flow", e)
                exitProcess(1)
            }
            
            logger.info("Starting EWT processing...")
            val ewtDF = try {
                ProcessEWT().process()
            } catch (e: Exception) {
                logger.error("Failed to process EWT", e)
                exitProcess(1)
            }

            logger.info("Merging and filtering data...")
            // Extract Link Metadata (Length, Frequency)
            val linkMetaDF = metadata.linkData.map { (id, meta) ->
                mapOf(
                    "link_id" to id.toString(),
                    "bus_frequency" to meta.busFreq,
                    "length" to meta.length,
                    "bus_capacity" to metadata.busCap
                )
            }.toDataFrame()

            @Suppress("RemoveExplicitTypeArguments")
            val merged = avgLoadFactorDF
                .leftJoin(vehicleFlowDF, "link_id")
                .leftJoin(ewtDF, "link_id")
                .leftJoin(linkMetaDF, "link_id")
                .filter {
                    ("bus_frequency"<Double?>() ?: 0.0) > 0.0 &&
                    ("bus_link_avg_speed"<Double?>() ?: 0.0) > 0.0 &&
                    ("length"<Double?>() ?: 0.0) > 0.0
                }

            merged.writeArrowIPC(losOutput)
        }
        logger.info("MATSim data processing completed in {}", time)
    }
}
