package com.thomas.pt.data.processor

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.utility.Utility
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.AnyRow
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.readArrowIPC
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.pt.transitSchedule.api.TransitLine
import java.nio.file.Path

class MATSimProcessor(configPath: Path) {
    private val linkDataFrame: AnyFrame
    private val busDelayDataFrame: AnyFrame

    private val metadata by lazy { MATSimMetadataStore.metadata }

    private val passengerThreshold: Int

    init {
        val yamlConfig = Utility.loadYaml(configPath)
        val dataOutConfig = Utility.getYamlSubconfig(yamlConfig, "files", "data")
        linkDataFrame = DataFrame.readArrowIPC(dataOutConfig["link_records"] as String)
            .add("duration") { "exit_time"<Double>() - "enter_time"<Double>() }

        busDelayDataFrame = DataFrame.readArrowIPC(dataOutConfig["stop_records"] as String)
        passengerThreshold = Utility.getYamlSubconfig(yamlConfig, "scoring", "wait_ride").let {
            it["total_load_threshold"] as Int
        }
    }

    private fun sliceRow(row: AnyRow): List<Map<String, Any?>> {
        val enter = row["enter_time"] as Double
        val exit = row["exit_time"] as Double
        val dist = row["travel_distance"] as Double
        val duration = row["duration"] as Double

        if (duration <= 0) return emptyList()

        val startHour = (enter / 3600).toInt()
        val endHour = ((exit - 1) / 3600).toInt()

        return (startHour..endHour).mapNotNull { hour ->
            val sliceStart = enter.coerceAtLeast(hour * 3600.0)
            val sliceEnd = exit.coerceAtMost((hour + 1) * 3600.0)
            val sliceDuration = sliceEnd - sliceStart

            if (sliceDuration <= 0) null
            else row.toMap() + mapOf(
                "hour" to hour,
                "slice_start" to sliceStart,
                "duration" to sliceDuration,
                "travel_distance" to dist * (sliceDuration / duration)
            )
        }
    }

    private fun AnyFrame.sliceByHour(): AnyFrame
        = this.rows().flatMap(::sliceRow).toDataFrame()

    private fun processAvgTripLength(): Double
        = linkDataFrame
            .filter { "travel_distance"<Double>() > 0 }
            .groupBy("vehicle_id", "trip_id")
            .sum("travel_distance", name = "total_distance")
            .mean("total_distance")

    private inner class ProcessAvgLoadFactor {
        private val dfWithLoad = linkDataFrame
            .filter { "is_bus"() }
            .add("lf") { "passenger_load"<Int>().toDouble() / metadata.busCap }
            .add("pax_seconds") { "passenger_load"<Int>() * "duration"<Double>() }
            .add("weighted_lf") { "lf"<Double>() * "pax_seconds"<Double>() }
            .add("time_weighted_lf") { "lf"<Double>() * "duration"<Double>() }

        private fun aggregateBusLoadData(vararg groupBy: String): AnyFrame
            = dfWithLoad.groupBy(*groupBy).aggregate {
                sum("passenger_load") into "total_passengers"
                sum("weighted_lf") into "total_weighted_lf"
                sum("pax_seconds") into "total_pax_seconds"
                sum("time_weighted_lf") into "total_time_weighted_lf"
                sum("duration") into "total_duration"
            }

        private fun computePrimaryLF(df: AnyFrame, vararg groupBy: String): AnyFrame
            = df
                .filter { "total_passengers"<Int>() >= passengerThreshold }
                .add("avg_lf") { "total_weighted_lf"<Double>() / "total_pax_seconds"<Double>() }
                .select(*groupBy, "avg_lf")

        private fun computeFallbackLF(df: AnyFrame, vararg groupBy: String): AnyFrame
            = df
                .filter { "total_passengers"<Int>() < passengerThreshold }
                .add("avg_lf") { "total_time_weighted_lf"<Double>() / "total_duration"<Double>() }
                .select(*groupBy, "avg_lf")

        fun process(): AnyFrame {
            val lineGroup = arrayOf("link_id", "line_id")
            val dfLineStat = aggregateBusLoadData(*lineGroup)
            val busLineLoad = computePrimaryLF(dfLineStat, *lineGroup)
                .concat(computeFallbackLF(dfLineStat, *lineGroup))

            val linkGroup = arrayOf("link_id")
            val dfLinkStat = aggregateBusLoadData(*linkGroup)
            val busLinkLoad = computePrimaryLF(dfLinkStat, *linkGroup)
                .concat(computeFallbackLF(dfLinkStat, *linkGroup))

            val busLineLoadMap = busLineLoad
                .rows()
                .groupBy { it["link_id"] as String }
                .mapValues { (_, rows) ->
                    rows.associate { row ->
                        row["line_id"] as String to row["avg_lf"] as Double
                    }
                }

            return busLinkLoad
                .add("line_avg_lf") { busLineLoadMap["link_id"()] ?: emptyMap() }
                .select("link_id", "avg_lf", "line_avg_lf")
        }
    }

    private inner class ProcessVehicleFlow {
        private val dfWithSlicedFlow = linkDataFrame.sliceByHour()

        fun process(): AnyFrame {
            val dfStat = dfWithSlicedFlow
                .groupBy("link_id", "hour")
                .aggregate {
                    sum("travel_distance") into "hourly_distance"
                    sum("duration") into "hourly_duration"
                    count() into "vehicle_count"
                }
                .groupBy("link_id")
                .aggregate {
                    mean("vehicle_count") into "veh_flow"
                    sum("hourly_distance") into "total_distance"
                    sum("hourly_duration") into "total_duration"
                }
                .add("avg_speed") { "total_distance"<Double>() / "total_duration"<Double>() }
                .remove("total_distance", "total_duration")

            val dfBusFlow = dfWithSlicedFlow.filter { "is_bus"() }
            val dfBusLineSpeed = dfBusFlow
                .groupBy("link_id", "line_id")
                .aggregate {
                    sum("travel_distance") into "line_travel_distance"
                    sum("duration") into "line_duration"
                }
                .add("line_avg_speed") { "line_travel_distance"<Double>() / "line_duration"<Double>() }
                .select("link_id", "line_id", "line_avg_speed")
            val dfBusLinkSpeed = dfBusFlow
                .groupBy("link_id")
                .aggregate {
                    sum("travel_distance") into "link_travel_distance"
                    sum("duration") into "link_duration"
                }
                .add("bus_link_avg_speed") { "link_travel_distance"<Double>() / "link_duration"<Double>() }
                .select("link_id", "bus_link_avg_speed")

            val busLineSpeedMap = dfBusLineSpeed
                .rows()
                .groupBy { it["link_id"] as String }
                .mapValues { (_, rows) ->
                    rows.associate { row ->
                        row["line_id"] as String to row["line_avg_speed"] as Double
                    }
                }

            return dfStat
                .leftJoin(dfBusLinkSpeed) { "link_id"<String>() }
                .fillNulls("bus_link_avg_speed").withZero()
                .add("bus_line_avg_speed") { busLineSpeedMap["link_id"()] ?: emptyMap() }
                .select("link_id", "veh_flow", "avg_speed", "bus_link_avg_speed", "bus_line_avg_speed")
        }
    }

    private inner class ProcessEWT {
        // TODO: Process EWT from bus delay data

        private val dfWithEWT = busDelayDataFrame
            .add("weighted_deviation") { "schedule_deviation"<Double>() * "boarding"<Int>() }

        private fun ewtAggregation(df: AnyFrame, vararg groupBy: String): AnyFrame
            = df.groupBy(*groupBy)
                .aggregate {
                    sum("weighted_deviation") into "sum_weighted_deviation"
                    sum("boarding") into "sum_weight"
                    mean("schedule_deviation") into "mean_delay"
                }
                .add("ewt") {
                    if ("sum_weight"<Int>() >= passengerThreshold)
                        "sum_weighted_deviation"<Double>() / "sum_weight"<Int>()
                    else "mean_delay"()
                }
                .select(*groupBy, "ewt")

        private fun interpolateEWTForLine(
            ewtAtStops: Map<String, Map<String, Double>>
        ): AnyFrame {
            val acc = mutableMapOf<Pair<Id<Link>, Id<TransitLine>>, MutableList<Double>>()
            val linkLengths: Map<Id<Link>, Double> = metadata.linkData.mapValues {
                (_, linkMeta) -> linkMeta.length
            }

            metadata.busRoutes.forEach { (lineId, lineRoutes) ->
                val resultAccumulator: (Id<Link>, Double) -> Unit = { linkId, ewt ->
                    acc.getOrPut(linkId to lineId) { mutableListOf() }.add(ewt)
                }

                for (route in lineRoutes) {
                    val stopsEWTIdx = route.route.mapIndexedNotNull { index, linkId ->
                        if (ewtAtStops["$linkId"]?.containsKey("$lineId") == true) index else null
                    }
                    if (stopsEWTIdx.isEmpty()) continue

                    val firstIdx = stopsEWTIdx.first()
                    val firstEWT = ewtAtStops["${route.route[firstIdx]}"]!!["$lineId"]!!
                    (0..firstIdx).forEach { resultAccumulator(route.route[it], firstEWT) }

                    (0 until stopsEWTIdx.size - 1).forEach { i ->
                        val startIdx = stopsEWTIdx[i]
                        val endIdx = stopsEWTIdx[i + 1]
                        val startEWT = ewtAtStops["${route.route[startIdx]}"]!!["$lineId"]!!
                        val endEWT = ewtAtStops["${route.route[endIdx]}"]!!["$lineId"]!!
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
                    val lastEWT = ewtAtStops["${route.route[lastIdx]}"]!!["$lineId"]!!
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

        private fun interpolateEWTForLink(
            dfInterpolated: AnyFrame,
        ): AnyFrame = dfInterpolated
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
            .add("ewt") {
                if ("weight_sum"<Double>() > 0)
                    "weighted_ewt_sum"<Double>() / "weight_sum"<Double>()
                else "mean_ewt"()
            }
            .select("link_id", "ewt")

//        fun process(): AnyFrame {
//            val dfEWTPerLine = ewtAggregation(dfWithEWT, "link_id", "line_id")
//            val dfEWTPerLink = ewtAggregation(dfWithEWT, "link_id")
//                .rename("ewt" to "measured_ewt")
//
//            val ewtLineMap = dfEWTPerLine
//                .rows()
//                .groupBy { it["link_id"] as String }
//                .mapValues { (_, rows) ->
//                    rows.associate { row ->
//                        row["line_id"] as String to row["ewt"] as Double
//                    }
//                }
//
//            val ewtLinkMap = dfEWTPerLink
//                .rows()
//                .associate { row ->
//                    row["link_id"] as String to row["measured_ewt"] as Double
//                }
//
//            val interpolatedPerLine = interpolateEWTForLine(ewtLineMap)
//            val interpolatedPerLink = interpolateEWTForLink(interpolatedPerLine)
//        }
    }
}