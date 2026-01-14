package com.thomas.pt.data.writer

import com.thomas.pt.data.model.extractor.BusPassengerData
import com.thomas.pt.data.schema.BusPassengerDataSchema
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File

class ArrowBusPassengerDataWriter(
    outputPath: File,
    batchSize: Int
): ArrowBatchWriter<BusPassengerData>(outputPath, batchSize) {
    override val schema: Schema = BusPassengerDataSchema.schema

    private lateinit var busId: VarCharVector
    private lateinit var personId: VarCharVector

    override fun allocateVectors(capacity: Int) {
        busId = (root.getVector("bus_id") as VarCharVector).apply { allocateNew(capacity) }
        personId = (root.getVector("person_id") as VarCharVector).apply { allocateNew(capacity) }
    }

    override fun populateRow(idx: Int, item: BusPassengerData) {
        busId.setSafe(idx, item.busId.toByteArray())
        personId.setSafe(idx, item.personId.toByteArray())
    }
}