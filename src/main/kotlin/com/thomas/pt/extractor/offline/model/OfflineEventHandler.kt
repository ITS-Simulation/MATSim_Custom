package com.thomas.pt.extractor.offline.model

interface OfflineEventHandler {
    fun handleEvent(time: Double, type: String, attributes: Map<String, String>)
}