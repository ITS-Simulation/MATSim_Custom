package com.thomas.pt.writer.core

import com.thomas.pt.model.data.BusDelayData
import com.thomas.pt.model.data.BusPassengerData
import com.thomas.pt.model.data.BusTripData
import com.thomas.pt.model.data.TripData
import com.thomas.pt.model.extractor.DataEndpoints
import com.thomas.pt.writer.arrow.ArrowBusDelayDataWriter
import com.thomas.pt.writer.arrow.ArrowBusPassengerDataWriter
import com.thomas.pt.writer.arrow.ArrowBusTripDataWriter
import com.thomas.pt.writer.arrow.ArrowTripDataWriter
import com.thomas.pt.writer.csv.CSVBusDelayDataWriter
import com.thomas.pt.writer.csv.CSVBusPassengerDataWriter
import com.thomas.pt.writer.csv.CSVBusTripDataWriter
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
    files: DataEndpoints,
    batchSize: Int,
    format: WriterFormat,
): AutoCloseable {
    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 200_000
    }

    private val busPassengerDataChannel = Channel<BusPassengerData>(DEFAULT_CHANNEL_CAPACITY)
    private val busDelayChannel = Channel<BusDelayData>(DEFAULT_CHANNEL_CAPACITY)
    private val tripDataChannel = Channel<TripData>(DEFAULT_CHANNEL_CAPACITY)
    private val busTripDataChannel = Channel<BusTripData>(DEFAULT_CHANNEL_CAPACITY)

    private val busPassengerDataWriter: GenericWriter<BusPassengerData>
    private val busDelayWriter: GenericWriter<BusDelayData>
    private val tripDataWriter: GenericWriter<TripData>
    private val busTripDataWriter: GenericWriter<BusTripData>

    private val busPassengerDataWriterJob: Job
    private val busDelayWriterJob: Job
    private val tripWriterJob: Job
    private val busTripWriterJob: Job

    init {
        val busPaxDataFile = File(format.resolveExtension(files.busPassengerDataFile))
        val busDelayDataFile = File(format.resolveExtension(files.busDelayDataFile))
        val busTripDataFile = File(format.resolveExtension(files.busTripDataFile))
        val tripDataFile = File(format.resolveExtension(files.tripDataFile))

        busPaxDataFile.absoluteFile.parentFile.mkdirs()
        busDelayDataFile.absoluteFile.parentFile.mkdirs()
        busTripDataFile.absoluteFile.parentFile.mkdirs()
        tripDataFile.absoluteFile.parentFile.mkdirs()

        when (format) {
            WriterFormat.ARROW -> {
                busPassengerDataWriter = ArrowBusPassengerDataWriter(busPaxDataFile, batchSize)
                busDelayWriter = ArrowBusDelayDataWriter(busDelayDataFile, batchSize)
                busTripDataWriter = ArrowBusTripDataWriter(busTripDataFile, batchSize)
                tripDataWriter = ArrowTripDataWriter(tripDataFile, batchSize)
            }

            WriterFormat.CSV -> {
                busPassengerDataWriter = CSVBusPassengerDataWriter(busPaxDataFile, batchSize)
                busDelayWriter = CSVBusDelayDataWriter(busDelayDataFile, batchSize)
                busTripDataWriter = CSVBusTripDataWriter(busTripDataFile, batchSize)
                tripDataWriter = CSVTripDataWriter(tripDataFile, batchSize)
            }
        }

        busPassengerDataWriterJob = scope.launch {
            busPassengerDataChannel.consumeAsFlow().collect(busPassengerDataWriter::write)
        }
        busDelayWriterJob = scope.launch {
            busDelayChannel.consumeAsFlow().collect(busDelayWriter::write)
        }
        busTripWriterJob = scope.launch {
            busTripDataChannel.consumeAsFlow().collect(busTripDataWriter::write)
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

    fun pushBusTripData(item: BusTripData): Boolean
        = busTripDataChannel.trySend(item).isSuccess

    override fun close() {
        busPassengerDataChannel.close()
        busDelayChannel.close()
        busTripDataChannel.close()
        tripDataChannel.close()

        runBlocking {
            busPassengerDataWriterJob.join()
            busDelayWriterJob.join()
            busTripWriterJob.join()
            tripWriterJob.join()
        }

        busPassengerDataWriter.close()
        busDelayWriter.close()
        busTripDataWriter.close()
        tripDataWriter.close()
    }
}