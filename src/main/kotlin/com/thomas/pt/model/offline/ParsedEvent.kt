package com.thomas.pt.model.offline

data class ParsedEvent(
    val time: Double,
    val type: String,
    val attributes: Map<String, String>
)