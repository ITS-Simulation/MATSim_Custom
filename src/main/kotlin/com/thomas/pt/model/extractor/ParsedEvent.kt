package com.thomas.pt.model.extractor

data class ParsedEvent(
    val time: Double,
    val type: String,
    val attributes: Map<String, String>
)