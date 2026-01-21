package com.thomas.pt.model.data

import org.jetbrains.kotlinx.dataframe.annotations.ColumnName
import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

@DataSchema
data class BusDelayData(
    @ColumnName("stop_id")
    val stopId: String,
    @ColumnName("arrival_delay")
    val arrDelay: Double,
    @ColumnName("depart_delay")
    val depDelay: Double
)