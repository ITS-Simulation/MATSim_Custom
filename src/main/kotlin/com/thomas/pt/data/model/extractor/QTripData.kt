package com.thomas.pt.data.model.extractor

data class QTripData(
    val personId: String,
    val mainMode: String,
    val startTime: Double,
    val travelTime: Double,
    val vehIDList: List<String>,

    )