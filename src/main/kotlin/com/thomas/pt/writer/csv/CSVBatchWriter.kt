package com.thomas.pt.writer.csv

import com.thomas.pt.writer.core.GenericWriter

abstract class CSVBatchWriter<T>(
    private val batchSize: Int
) : GenericWriter<T> {
    abstract val data: MutableList<T>

    abstract fun writeHeader()

    abstract fun flush()

    private var currIdx: Int = 0

    override fun write(item: T) {
        data.add(item)
        currIdx++

        if (currIdx >= batchSize) {
            flush()
            currIdx = 0
        }
    }

    override fun close() {
        if (data.isNotEmpty()) { flush() }
        data.clear()
    }
}