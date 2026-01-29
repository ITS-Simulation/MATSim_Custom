package com.thomas.pt.scoring

import kotlin.collections.get
import kotlin.math.absoluteValue
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

data class ScoringWeights(
    val transitRouteRatio: Double,
    val serviceCoverage: Double,
    val ridership: Double,
    val travelTime: Double,
    val transitAutoTimeRatio: Double,
    val onTimePerf: Double,
    val productivity: Double,
    val busEfficiency: Double,
    val busEffectiveTravelDistance: Double,
    val busTransferRate: Double,
) {
    companion object {
        fun fromYaml(map: Map<*, *>): ScoringWeights {
            return ScoringWeights(
                transitRouteRatio = map["transit_route_ratio"] as Double,
                serviceCoverage = map["service_coverage"] as Double,
                ridership = map["ridership"] as Double,
                travelTime = map["travel_time"] as Double,
                transitAutoTimeRatio = map["transit_auto_time_ratio"] as Double,
                onTimePerf = map["on_time_performance"] as Double,
                productivity = map["productivity"] as Double,
                busEfficiency = map["bus_efficiency"] as Double,
                busEffectiveTravelDistance = map["bus_effective_travel_dist"] as Double,
                busTransferRate = map["bus_transfer"] as Double,
            )
        }
    }

    init {
        val total = this::class.memberProperties
            .filterIsInstance<KProperty1<ScoringWeights, Double>>()
            .sumOf { it.get(this) }

        val epsilon = 1e-9
        require((total - 1.0).absoluteValue < epsilon) {
            "Weights must sum to 1.0, but got $total"
        }
    }
}