package com.thomas.pt.writer.arrow

import com.thomas.pt.model.data.BusDelayData
import com.thomas.pt.writer.arrow.schema.BusDelayDataSchema
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File

class ArrowBusDelayDataWriter(
    outputPath: File,
    batchSize: Int
): ArrowBatchWriter<BusDelayData>(outputPath, batchSize) {
    override val schema: Schema = BusDelayDataSchema.schema

    private lateinit var stopId: VarCharVector
    private lateinit var arrDelay: Float8Vector
    private lateinit var depDelay: Float8Vector

    override fun allocateVectors(capacity: Int) {
        stopId = (root.getVector("stop_id") as VarCharVector).apply { allocateNew(capacity) }
        arrDelay = (root.getVector("arrival_delay") as Float8Vector).apply { allocateNew(capacity) }
        depDelay = (root.getVector("depart_delay") as Float8Vector).apply { allocateNew(capacity) }
    }

    override fun populateRow(idx: Int, item: BusDelayData) {
        stopId.setSafe(idx, item.stopId.toByteArray())
        arrDelay.setSafe(idx, item.arrDelay)
        depDelay.setSafe(idx, item.depDelay)
    }
}