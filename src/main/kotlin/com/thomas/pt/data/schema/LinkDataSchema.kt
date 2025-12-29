package com.thomas.pt.data.schema

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

object LinkDataSchema {
    val schema: Schema = Schema(listOf(
        Field("vehicle_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        Field("link_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        Field("line_id", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
        Field("trip_id", FieldType.notNullable(ArrowType.Int(32, true)), null),
        Field("enter_time", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
        Field("exit_time", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
        Field("travel_distance", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
        Field("passenger_load", FieldType.nullable(ArrowType.Int(32, true)), null),
        Field("is_bus", FieldType.notNullable(ArrowType.Bool.INSTANCE), null)
    ))
}