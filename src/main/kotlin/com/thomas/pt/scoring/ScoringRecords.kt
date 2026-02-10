package com.thomas.pt.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ScoringRecords(
    @SerialName("transit_route_ratio")
    val transitRouteRatio: Double,
    @SerialName("service_coverage")
    val serviceCoverage: Double,
    @SerialName("ridership")
    val ridership: Double,
    @SerialName("travel_time")
    val travelTime: Double,
    @SerialName("transit_auto_time_ratio")
    val transitAutoTimeRatio: Double,
    @SerialName("on_time_perf")
    val onTimePerf: Double,
    @SerialName("productivity")
    val productivity: Double,
    @SerialName("bus_efficiency")
    val busEfficiency: Double,
    @SerialName("bus_effective_travel_distance")
    val busEffectiveTravelDistance: Double,
    @SerialName("bus_transfer_rate")
    val busTransferRate: Double,
    @SerialName("final_score")
    val finalScore: Double,
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
    fun writeJson(out: File) = out.writeText(
        json.encodeToString(serializer(), this)
    )
}