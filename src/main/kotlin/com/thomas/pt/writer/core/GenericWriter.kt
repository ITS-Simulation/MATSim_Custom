package com.thomas.pt.writer.core

interface GenericWriter<T>: AutoCloseable {
    fun write(item: T)
}