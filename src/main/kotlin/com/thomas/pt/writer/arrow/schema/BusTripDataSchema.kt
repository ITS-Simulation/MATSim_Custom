package com.thomas.pt.writer.arrow.schema

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

object BusTripDataSchema {
    val schema: Schema = Schema(
        listOf(
            Field("bus_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("link_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("link_length", FieldType.notNullable(
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            ), null),
            Field("travel_time", FieldType.notNullable(
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            ), null),
            Field("have_passenger", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
        )
    )
}