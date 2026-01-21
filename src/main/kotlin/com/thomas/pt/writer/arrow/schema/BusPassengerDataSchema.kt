package com.thomas.pt.writer.arrow.schema

import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

object BusPassengerDataSchema {
    val schema: Schema = Schema(
        listOf(
            Field("person_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("bus_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        )
    )
}