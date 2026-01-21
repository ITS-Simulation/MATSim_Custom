package com.thomas.pt.writer.arrow

import com.thomas.pt.model.data.TripData
import com.thomas.pt.writer.arrow.schema.TripDataSchema
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.complex.ListVector
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File

class ArrowTripDataWriter(
    outputFile: File,
    batchSize: Int
): ArrowBatchWriter<TripData>(outputFile, batchSize) {
    override val schema: Schema = TripDataSchema.schema

    private lateinit var personId: VarCharVector
    private lateinit var startTime: Float8Vector
    private lateinit var travelTime: Float8Vector
    private lateinit var mainMode: VarCharVector
    private lateinit var vehList: ListVector

    override fun allocateVectors(capacity: Int) {
        personId = (root.getVector("person_id") as VarCharVector).apply { allocateNew(capacity) }
        startTime = (root.getVector("start_time") as Float8Vector).apply { allocateNew(capacity) }
        travelTime = (root.getVector("travel_time") as Float8Vector).apply { allocateNew(capacity) }
        mainMode = (root.getVector("main_mode") as VarCharVector).apply { allocateNew(capacity) }
        vehList = (root.getVector("veh_list") as ListVector).apply { allocateNew() }
    }

    override fun populateRow(idx: Int, item: TripData) {
        personId.setSafe(idx, item.personId.toByteArray())
        startTime.setSafe(idx, item.startTime)
        travelTime.setSafe(idx, item.travelTime)
        mainMode.setSafe(idx, item.mainMode.toByteArray())
        val vehDataVector = vehList.dataVector as VarCharVector
        val startOffset = vehList.startNewValue(idx)
        item.vehList.forEachIndexed { i, veh ->
            vehDataVector.setSafe(startOffset + i, veh.toByteArray())
        }
        vehList.endValue(idx, item.vehList.size)
    }
}