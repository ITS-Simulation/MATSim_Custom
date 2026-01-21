package com.thomas.pt.extractor.offline.model

import com.thomas.pt.model.offline.ParsedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.kobjects.ktxml.api.EventType
import org.kobjects.ktxml.mini.MiniXmlPullParser
import java.io.File
import java.io.FileReader
import java.util.zip.GZIPInputStream

class OfflineEventParser(private val file: File) {
    private fun eventFlow(): Flow<ParsedEvent> = flow {
        val reader = if (file.name.endsWith(".gz")) {
             GZIPInputStream(file.inputStream()).reader()
        } else { file.reader() }
        val iterator = StreamingCharIterator(reader, 32)
        val parser = MiniXmlPullParser(iterator)

        reader.use {
            while (parser.eventType != EventType.END_DOCUMENT) {
                if (parser.eventType == EventType.START_TAG && parser.name == "event") {
                    val attrs = extractAttributes(parser)
                    val time = attrs["time"]?.toDoubleOrNull() ?: 0.0
                    val type = attrs["type"] ?: ""

                    emit(ParsedEvent(time, type, attrs))
                }
                parser.next()
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun parseEventsAsync(vararg handlers: OfflineEventHandler) = coroutineScope {
        val channels = handlers.map { Channel<ParsedEvent>(capacity = 200_000) }
        
        handlers.forEachIndexed { index, handler ->
            launch(Dispatchers.Default) {
                for (event in channels[index]) {
                    handler.handleEvent(event.time, event.type, event.attributes)
                }
            }
        }
        
        eventFlow().collect { event ->
            channels.forEach {
                assert(it.trySend(event).isSuccess)
            }
        }
        
        channels.forEach { it.close() }
    }

    private fun extractAttributes(parser: MiniXmlPullParser): Map<String, String> {
        val count = parser.attributeCount
        if (count == 0) return emptyMap()
        
        val attrs = HashMap<String, String>(count)
        for (i in 0 until count) {
            attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
        }
        return attrs
    }
}
