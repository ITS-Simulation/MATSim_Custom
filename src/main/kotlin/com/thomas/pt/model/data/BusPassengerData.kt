package com.thomas.pt.model.data

import org.jetbrains.kotlinx.dataframe.annotations.ColumnName
import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

@DataSchema
data class BusPassengerData (
    @ColumnName("person_id")
    val personId: String,
    @ColumnName("bus_id")
    val busId: String
)
