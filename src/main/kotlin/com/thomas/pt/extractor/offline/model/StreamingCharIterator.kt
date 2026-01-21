package com.thomas.pt.extractor.offline.model

import java.io.Reader

class StreamingCharIterator(
    private val reader: Reader,
    bufferSize: Int,
) : CharIterator() {
    private val buffer = CharArray(1024 * bufferSize)
    private var index = 0
    private var size = 0
    private var isClosed = false

    private fun fillBuffer() {
        if (isClosed) return
        
        
        size = reader.read(buffer)
        index = 0
        
        if (size == -1) {
            isClosed = true
            size = 0
        }
    }

    override fun hasNext(): Boolean {
        if (index >= size) {
            fillBuffer()
        }
        return size > 0 && index < size
    }

    override fun nextChar(): Char {
        if (index >= size) {
            fillBuffer()
        }
        if (index >= size) throw NoSuchElementException("EOF")
        return buffer[index++]
    }
}
