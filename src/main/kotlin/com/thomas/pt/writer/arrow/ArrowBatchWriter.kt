package com.thomas.pt.writer.arrow

import com.thomas.pt.writer.core.GenericWriter
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File
import java.nio.channels.Channels

abstract class ArrowBatchWriter<T>(
    outputFile: File,
    private val batchSize: Int
): GenericWriter<T> {
    protected val allocator: BufferAllocator = RootAllocator()
    protected abstract val schema: Schema

    protected val root: VectorSchemaRoot by lazy {
        VectorSchemaRoot.create(schema, allocator)
    }

    private val outputStream = outputFile.outputStream()
    private val writer: ArrowStreamWriter by lazy {
        ArrowStreamWriter(root, null, Channels.newChannel(outputStream))
    }

    private var currIdx = 0
    private var started = false

    protected abstract fun allocateVectors(capacity: Int)

    protected abstract fun populateRow(idx: Int, item: T)

    override fun write(item: T) {
        if (!started) {
            allocateVectors(batchSize)
            writer.start()
            started = true
        }

        populateRow(currIdx, item)
        currIdx++

        if (currIdx >= batchSize) {
            flush()
        }
    }

    private fun flush() {
        if (currIdx == 0) return

        root.rowCount = currIdx
        writer.writeBatch()
        root.clear()
        allocateVectors(batchSize)
        currIdx = 0
    }

    override fun close() {
        try {
            if (started) {
                flush()
                writer.end()
                writer.close()
            }
        } finally {
            outputStream.close()
            root.close()
            allocator.close()
        }
    }
}