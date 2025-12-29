package com.thomas.pt.data.model.extractor

data class LinkData(
    val vehicleId: String,
    val linkId: String,
    val lineId: String?,
    val tripId: Int,
    val enterTime: Double,
    val exitTime: Double,
    val travelDistance: Double,
    val passengerLoad: Int?,
    val isBus: Boolean
)