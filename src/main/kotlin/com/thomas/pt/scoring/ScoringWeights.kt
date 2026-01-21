package com.thomas.pt.scoring

data class ScoringWeights(
    val serviceCoverage: Double,
    val ridership: Double,
    val travelTime: Double,
    val transitAutoTimeRatio: Double,
    val onTimePerf: Double,
    val productivity: Double,
    val busEfficiency: Double,
    val busEffectiveTravelDistance: Double,
) {
    init {
        val total = serviceCoverage + ridership + travelTime + transitAutoTimeRatio +
            onTimePerf + productivity + busEfficiency + busEffectiveTravelDistance
        require(total == 1.0) { "Weights must sum to 1.0, but got $total" }
    }
}