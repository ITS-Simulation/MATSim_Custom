package com.thomas.pt.data.writer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import com.thomas.pt.data.model.extractor.BusDelayData
import com.thomas.pt.data.model.extractor.LinkData
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class MATSimEventWriter(
    scope: CoroutineScope,
    linkDataPath: Path,
    busDelayDataPath: Path,
    batchSize: Int,
): AutoCloseable {
    private val linkDataChannel = Channel<LinkData>(200_000)
    private val busDelayChannel = Channel<BusDelayData>(200_000)

    private val linkDataWriter = ArrowLinkDataWriter(linkDataPath, batchSize = batchSize)
    private val busDelayDataWriter = ArrowBusDelayDataWriter(busDelayDataPath, batchSize = batchSize)

    private val linkWriterJob: Job = scope.launch {
        linkDataChannel.consumeAsFlow().collect(linkDataWriter::write)
    }
    private val busDelayWriterJob: Job = scope.launch {
        busDelayChannel.consumeAsFlow().collect(busDelayDataWriter::write)
    }

    init {
        linkDataPath.parent.toFile().mkdirs()
        busDelayDataPath.parent.toFile().mkdirs()
    }

    fun pushLinkData(item: LinkData): Boolean
        = linkDataChannel.trySend(item).isSuccess

    fun pushBusDelayData(item: BusDelayData): Boolean
        = busDelayChannel.trySend(item).isSuccess

    override fun close() {
        linkDataChannel.close()
        busDelayChannel.close()

        runBlocking {
            linkWriterJob.join()
            busDelayWriterJob.join()
        }

        linkDataWriter.close()
        busDelayDataWriter.close()
    }
}