package com.thomas.pt.data.model.extractor

data class BusDelayData(
    val vehicleId: String,
    val stopId: String,
    val linkId: String,
    val lineId: String,
    val timestamp: Double,
    val scheduleDev: Double,
    val boarding: Int,
    val alighting: Int,
)