package com.thomas.pt.writer.arrow

import com.thomas.pt.model.data.BusTripData
import com.thomas.pt.writer.arrow.schema.BusTripDataSchema
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File

class ArrowBusTripDataWriter(
    outputFile: File,
    batchSize: Int
): ArrowBatchWriter<BusTripData>(outputFile, batchSize) {
    override val schema: Schema = BusTripDataSchema.schema

    private lateinit var busId: VarCharVector
    private lateinit var linkId: VarCharVector
    private lateinit var havePassenger: BitVector

    override fun allocateVectors(capacity: Int) {
        busId = (root.getVector("bus_id") as VarCharVector).apply { allocateNew(capacity) }
        linkId = (root.getVector("link_id") as VarCharVector).apply { allocateNew(capacity) }
        havePassenger = (root.getVector("have_passenger") as BitVector).apply { allocateNew(capacity) }
    }

    override fun populateRow(idx: Int, item: BusTripData) {
        busId.setSafe(idx, item.busId.toByteArray())
        linkId.setSafe(idx, item.linkId.toByteArray())
        havePassenger.setSafe(idx, if (item.havePassenger) 1 else 0)
    }
}