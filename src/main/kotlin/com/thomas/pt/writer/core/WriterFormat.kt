package com.thomas.pt.writer.core

enum class WriterFormat {
    ARROW, CSV;

    fun resolveExtension(filePath: String): String = when(this) {
        ARROW -> "$filePath.arrow"
        CSV -> "$filePath.csv"
    }
}