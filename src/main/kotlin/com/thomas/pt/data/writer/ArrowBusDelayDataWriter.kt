package com.thomas.pt.data.writer

import com.thomas.pt.data.model.extractor.BusDelayData
import com.thomas.pt.data.schema.BusDelayDataSchema
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.pojo.Schema
import java.nio.file.Path

class ArrowBusDelayDataWriter(
    outputPath: Path,
    batchSize: Int
): ArrowBatchWriter<BusDelayData>(outputPath, batchSize) {
    override val schema: Schema = BusDelayDataSchema.schema

    private lateinit var vehicleId: VarCharVector
    private lateinit var stopId: VarCharVector
    private lateinit var linkId: VarCharVector
    private lateinit var lineId: VarCharVector
    private lateinit var timestamp: Float8Vector
    private lateinit var scheduleDev: Float8Vector
    private lateinit var boarding: IntVector
    private lateinit var alighting: IntVector


    override fun allocateVectors(capacity: Int) {
        vehicleId = (root.getVector("vehicle_id") as VarCharVector).apply { allocateNew(capacity) }
        stopId = (root.getVector("stop_id") as VarCharVector).apply { allocateNew(capacity) }
        linkId = (root.getVector("link_id") as VarCharVector).apply { allocateNew(capacity) }
        lineId = (root.getVector("line_id") as VarCharVector).apply { allocateNew(capacity) }
        timestamp = (root.getVector("timestamp") as Float8Vector).apply { allocateNew(capacity) }
        scheduleDev = (root.getVector("schedule_deviation") as Float8Vector).apply { allocateNew(capacity) }
        boarding = (root.getVector("boarding") as IntVector).apply { allocateNew(capacity) }
        alighting = (root.getVector("alighting") as IntVector).apply { allocateNew(capacity) }
    }

    override fun populateRow(idx: Int, item: BusDelayData) {
        vehicleId.setSafe(idx, item.vehicleId.toByteArray())
        stopId.setSafe(idx, item.stopId.toByteArray())
        linkId.setSafe(idx, item.linkId.toByteArray())
        lineId.setSafe(idx, item.lineId.toByteArray())
        timestamp.setSafe(idx, item.timestamp)
        scheduleDev.setSafe(idx, item.scheduleDev)
        boarding.setSafe(idx, item.boarding)
        alighting.setSafe(idx, item.alighting)
    }
}