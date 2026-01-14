package com.thomas.pt.data.writer

import com.thomas.pt.data.model.extractor.BusDelayData
import com.thomas.pt.data.model.extractor.BusPassengerData
import com.thomas.pt.data.model.extractor.TripData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class MATSimEventWriter(
    scope: CoroutineScope,
    busPaxDataFile: File,
    busDelayDataFile: File,
    tripDataFile: File,
    batchSize: Int,
): AutoCloseable {
    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 200_000
    }

    private val busPassengerDataChannel = Channel<BusPassengerData>(DEFAULT_CHANNEL_CAPACITY)
    private val busDelayChannel = Channel<BusDelayData>(DEFAULT_CHANNEL_CAPACITY)
    private val tripDataChannel = Channel<TripData>(DEFAULT_CHANNEL_CAPACITY)

    private val busPassengerDataWriter = ArrowBusPassengerDataWriter(busPaxDataFile, batchSize)
    private val busDelayDataWriter = ArrowBusDelayDataWriter(busDelayDataFile, batchSize)
    private val tripDataWriter = ArrowTripDataWriter(tripDataFile, batchSize)

    private val busPassengerDataWriterJob: Job = scope.launch {
        busPassengerDataChannel.consumeAsFlow().collect(busPassengerDataWriter::write)
    }
    private val busDelayWriterJob: Job = scope.launch {
        busDelayChannel.consumeAsFlow().collect(busDelayDataWriter::write)
    }
    private val tripWriterJob: Job = scope.launch {
        tripDataChannel.consumeAsFlow().collect(tripDataWriter::write)
    }

    init {
        busPaxDataFile.absoluteFile.parentFile.mkdirs()
        busDelayDataFile.absoluteFile.parentFile.mkdirs()
        tripDataFile.absoluteFile.parentFile.mkdirs()
    }

    fun pushBusPassengerData(item: BusPassengerData): Boolean
        = busPassengerDataChannel.trySend(item).isSuccess

    fun pushBusDelayData(item: BusDelayData): Boolean
        = busDelayChannel.trySend(item).isSuccess

    fun pushTripData(item: TripData): Boolean
        = tripDataChannel.trySend(item).isSuccess

    override fun close() {
        busPassengerDataChannel.close()
        busDelayChannel.close()
        tripDataChannel.close()

        runBlocking {
            busPassengerDataWriterJob.join()
            busDelayWriterJob.join()
            tripWriterJob.join()
        }

        busPassengerDataWriter.close()
        busDelayDataWriter.close()
        tripDataWriter.close()
    }
}