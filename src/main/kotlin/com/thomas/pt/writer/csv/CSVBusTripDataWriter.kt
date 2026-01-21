package com.thomas.pt.writer.csv

import com.thomas.pt.model.data.BusTripData
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.io.File
import java.io.FileOutputStream

class CSVBusTripDataWriter(
    private val busTripDataFile: File,
    batchSize: Int
): CSVBatchWriter<BusTripData>(batchSize) {
    override val data: MutableList<BusTripData> = mutableListOf()

    init { writeHeader() }

    override fun writeHeader() =
        FileOutputStream(busTripDataFile).bufferedWriter().use { writer ->
            emptyList<BusTripData>().toDataFrame().writeCsv(writer)
        }

    override fun flush() =
        FileOutputStream(busTripDataFile, true).bufferedWriter().use { writer ->
            data.toDataFrame().writeCsv(writer, includeHeader = false)
            data.clear()
        }
}