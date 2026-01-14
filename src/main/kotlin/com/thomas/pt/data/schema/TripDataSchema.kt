package com.thomas.pt.data.schema

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

object TripDataSchema {
    val schema: Schema = Schema(
        listOf(
            Field("person_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("start_time", FieldType.notNullable(
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            ), null),
            Field("travel_time", FieldType.notNullable(
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            ), null),
            Field("main_mode", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("veh_list", FieldType.notNullable(ArrowType.List.INSTANCE), listOf(
                Field("veh", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null)
            ))
        )
    )
}