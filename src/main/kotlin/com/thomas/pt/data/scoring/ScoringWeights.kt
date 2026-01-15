package com.thomas.pt.data.scoring

data class ScoringWeights(
    val serviceCoverage: Double,
    val ridership: Double,
    val travelTime: Double,
    val transitAutoTimeRatio: Double,
    val onTimePerf: Double,
    val productivity: Double
)