package com.thomas.pt.writer.core

import com.thomas.pt.model.data.BusDelayData
import com.thomas.pt.model.data.BusPassengerData
import com.thomas.pt.model.data.TripData
import com.thomas.pt.writer.arrow.ArrowBusDelayDataWriter
import com.thomas.pt.writer.arrow.ArrowBusPassengerDataWriter
import com.thomas.pt.writer.arrow.ArrowTripDataWriter
import com.thomas.pt.writer.csv.CSVBusDelayDataWriter
import com.thomas.pt.writer.csv.CSVBusPassengerDataWriter
import com.thomas.pt.writer.csv.CSVTripDataWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class MATSimEventWriter(
    scope: CoroutineScope,
    busPaxData: String,
    busDelayData: String,
    tripData: String,
    batchSize: Int,
    format: WriterFormat,
): AutoCloseable {
    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 200_000
    }

    private val busPassengerDataChannel = Channel<BusPassengerData>(DEFAULT_CHANNEL_CAPACITY)
    private val busDelayChannel = Channel<BusDelayData>(DEFAULT_CHANNEL_CAPACITY)
    private val tripDataChannel = Channel<TripData>(DEFAULT_CHANNEL_CAPACITY)

    private val busPassengerDataWriter: GenericWriter<BusPassengerData>
    private val busDelayDataWriter: GenericWriter<BusDelayData>
    private val tripDataWriter: GenericWriter<TripData>

    private val busPassengerDataWriterJob: Job
    private val busDelayWriterJob: Job
    private val tripWriterJob: Job

    init {
        val busPaxDataFile = File(format.resolveExtension(busPaxData))
        val busDelayDataFile = File(format.resolveExtension(busDelayData))
        val tripDataFile = File(format.resolveExtension(tripData))

        when (format) {
            WriterFormat.ARROW -> {
                busPassengerDataWriter = ArrowBusPassengerDataWriter(busPaxDataFile, batchSize)
                busDelayDataWriter = ArrowBusDelayDataWriter(busDelayDataFile, batchSize)
                tripDataWriter = ArrowTripDataWriter(tripDataFile, batchSize)
            }
            WriterFormat.CSV -> {
                busPassengerDataWriter = CSVBusPassengerDataWriter(busPaxDataFile, batchSize)
                busDelayDataWriter = CSVBusDelayDataWriter(busDelayDataFile, batchSize)
                tripDataWriter = CSVTripDataWriter(tripDataFile, batchSize)
            }
        }


        busPaxDataFile.absoluteFile.parentFile.mkdirs()
        busDelayDataFile.absoluteFile.parentFile.mkdirs()
        tripDataFile.absoluteFile.parentFile.mkdirs()

        busPassengerDataWriterJob = scope.launch {
            busPassengerDataChannel.consumeAsFlow().collect(busPassengerDataWriter::write)
        }
        busDelayWriterJob = scope.launch {
            busDelayChannel.consumeAsFlow().collect(busDelayDataWriter::write)
        }
        tripWriterJob = scope.launch {
            tripDataChannel.consumeAsFlow().collect(tripDataWriter::write)
        }
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