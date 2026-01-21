package com.thomas.pt.model.data

import org.jetbrains.kotlinx.dataframe.annotations.ColumnName
import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

@DataSchema
data class TripData(
    @ColumnName("person_id")
    val personId: String,
    @ColumnName("start_time")
    val startTime: Double,
    @ColumnName("travel_time")
    val travelTime: Double,
    @ColumnName("main_mode")
    val mainMode: String,
    @ColumnName("veh_list")
    val vehList: List<String>,
)