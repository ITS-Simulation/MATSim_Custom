package com.thomas.pt.data.writer

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.Schema
import java.nio.channels.Channels
import java.nio.file.Path
import kotlin.io.path.outputStream

abstract class ArrowBatchWriter<T>(
    outputPath: Path,
    private val batchSize: Int
): AutoCloseable {
    protected val allocator: BufferAllocator = RootAllocator()
    protected abstract val schema: Schema

    protected val root: VectorSchemaRoot by lazy {
        VectorSchemaRoot.create(schema, allocator)
    }

    private val outputStream = outputPath.outputStream()
    private val writer: ArrowStreamWriter by lazy {
        ArrowStreamWriter(root, null, Channels.newChannel(outputStream))
    }

    private var currIdx = 0
    private var started = false

    protected abstract fun allocateVectors(capacity: Int)

    protected abstract fun populateRow(idx: Int, item: T)

    fun write(item: T) {
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