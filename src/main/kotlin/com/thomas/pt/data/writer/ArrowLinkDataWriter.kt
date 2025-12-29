package com.thomas.pt.data.writer

import com.thomas.pt.data.schema.LinkDataSchema
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.pojo.Schema
import com.thomas.pt.data.model.extractor.LinkData
import java.nio.file.Path

class ArrowLinkDataWriter(
    outputPath: Path,
    batchSize: Int
): ArrowBatchWriter<LinkData>(outputPath, batchSize) {
    override val schema: Schema = LinkDataSchema.schema

    private lateinit var vehicleId: VarCharVector
    private lateinit var linkId: VarCharVector
    private lateinit var lineId: VarCharVector
    private lateinit var tripId: IntVector
    private lateinit var enterTime: Float8Vector
    private lateinit var exitTime: Float8Vector
    private lateinit var travelDist: Float8Vector
    private lateinit var passengerLoad: IntVector
    private lateinit var isBus: BitVector

    override fun allocateVectors(capacity: Int) {
        vehicleId = (root.getVector("vehicle_id") as VarCharVector).apply { allocateNew(capacity) }
        linkId = (root.getVector("link_id") as VarCharVector).apply { allocateNew(capacity) }
        lineId = (root.getVector("line_id") as VarCharVector).apply { allocateNew(capacity) }
        tripId = (root.getVector("trip_id") as IntVector).apply { allocateNew(capacity) }
        enterTime = (root.getVector("enter_time") as Float8Vector).apply { allocateNew(capacity) }
        exitTime = (root.getVector("exit_time") as Float8Vector).apply { allocateNew(capacity) }
        travelDist = (root.getVector("travel_distance") as Float8Vector).apply { allocateNew(capacity) }
        passengerLoad = (root.getVector("passenger_load") as IntVector).apply { allocateNew(capacity) }
        isBus = (root.getVector("is_bus") as BitVector).apply { allocateNew(capacity) }
    }

    override fun populateRow(idx: Int, item: LinkData) {
        vehicleId.setSafe(idx, item.vehicleId.toByteArray())
        linkId.setSafe(idx, item.linkId.toByteArray())
        item.lineId?.let {
            lineId.setSafe(idx, it.toByteArray())
        } ?: lineId.setNull(idx)
        tripId.setSafe(idx, item.tripId)
        enterTime.setSafe(idx, item.enterTime)
        exitTime.setSafe(idx, item.exitTime)
        travelDist.setSafe(idx, item.travelDistance)
        item.passengerLoad?.let {
            passengerLoad.setSafe(idx, it)
        } ?: passengerLoad.setNull(idx)
        isBus.setSafe(idx, if (item.isBus) 1 else 0)
    }
}