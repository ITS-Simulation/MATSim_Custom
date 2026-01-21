package com.thomas.pt.writer.arrow.schema

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

object BusDelayDataSchema {
    val schema: Schema = Schema(
        listOf(
            Field("stop_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("arrival_delay", FieldType.notNullable(
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            ), null),
            Field("depart_delay", FieldType.notNullable(
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            ), null),
        )
    )
}