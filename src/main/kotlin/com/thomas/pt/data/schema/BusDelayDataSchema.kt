package com.thomas.pt.data.schema

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

object BusDelayDataSchema {
    val schema: Schema = Schema(listOf(
        Field("vehicle_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        Field("stop_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        Field("link_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        Field("line_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        Field("timestamp", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
        Field("schedule_deviation", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
        Field("boarding", FieldType.notNullable(ArrowType.Int(32, true)), null),
        Field("alighting", FieldType.notNullable(ArrowType.Int(32, true)), null),
    ))
}