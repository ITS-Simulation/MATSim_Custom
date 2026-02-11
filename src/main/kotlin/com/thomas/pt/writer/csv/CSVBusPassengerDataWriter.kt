package com.thomas.pt.writer.csv

import com.thomas.pt.model.data.BusPassengerData
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.io.File
import java.io.FileOutputStream

class CSVBusPassengerDataWriter(
    private val busPaxDataFile: File,
    batchSize: Int
) : CSVBatchWriter<BusPassengerData>(batchSize) {
    override val data: MutableList<BusPassengerData> = mutableListOf()

    init { writeHeader() }

    override fun writeHeader() =
        FileOutputStream(busPaxDataFile).bufferedWriter().use { writer ->
            emptyList<BusPassengerData>().toDataFrame().writeCsv(writer)
        }

    override fun flush() =
        FileOutputStream(busPaxDataFile, true).bufferedWriter().use { writer ->
            data.toDataFrame().writeCsv(writer, includeHeader = false)
            data.clear()
        }
}