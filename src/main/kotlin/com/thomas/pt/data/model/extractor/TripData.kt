package com.thomas.pt.data.model.extractor

data class TripData(
    val personId: String,
    val startTime: Double,
    val travelTime: Double,
    val mainMode: String,
    val vehList: List<String>,
)