package com.thomas.pt.writer.csv

import com.thomas.pt.model.data.BusDelayData
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.io.File
import java.io.FileOutputStream

class CSVBusDelayDataWriter(
    private val busDelayDataFile: File,
    batchSize: Int
) : CSVBatchWriter<BusDelayData>(batchSize) {
    override val data: MutableList<BusDelayData> = mutableListOf()

    init { writeHeader() }

    override fun writeHeader() =
        FileOutputStream(busDelayDataFile).bufferedWriter().use { writer ->
            emptyList<BusDelayData>().toDataFrame().writeCsv(writer)
        }

    override fun flush() =
        FileOutputStream(busDelayDataFile, true).bufferedWriter().use { writer ->
            data.toDataFrame().writeCsv(writer, includeHeader = false)
            data.clear()
        }
}
