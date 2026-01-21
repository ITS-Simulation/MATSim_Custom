package com.thomas.pt.model.data

import org.jetbrains.kotlinx.dataframe.annotations.ColumnName
import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

@DataSchema
data class BusTripData(
    @ColumnName("bus_id")
    val busId: String,
    @ColumnName("link_id")
    val linkId: String,
    @ColumnName("link_length")
    val linkLen: Double,
    @ColumnName("have_passenger")
    val havePassenger: Boolean
)
