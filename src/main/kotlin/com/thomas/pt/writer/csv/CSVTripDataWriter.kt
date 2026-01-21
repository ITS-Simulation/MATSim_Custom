package com.thomas.pt.writer.csv

import com.thomas.pt.model.data.TripData
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.io.File
import java.io.FileOutputStream

class CSVTripDataWriter(
    private val tripDataFile: File,
    batchSize: Int
) : CSVBatchWriter<TripData>(batchSize) {
    override val data: MutableList<TripData> = mutableListOf()

    init { writeHeader() }

    override fun writeHeader() =
        FileOutputStream(tripDataFile).bufferedWriter().use { writer ->
            emptyList<TripData>().toDataFrame().writeCsv(writer)
        }

    override fun flush() =
        FileOutputStream(tripDataFile, true).bufferedWriter().use { writer ->
            data.toDataFrame().writeCsv(writer, includeHeader = false)
            data.clear()
        }
}
