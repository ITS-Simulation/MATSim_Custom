package com.thomas.pt.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.extractor.offline.BusDelayExtractor
import com.thomas.pt.extractor.offline.BusPassengerExtractor
import com.thomas.pt.extractor.offline.BusTripExtractor
import com.thomas.pt.extractor.offline.TripExtractor
import com.thomas.pt.extractor.offline.model.OfflineEventParser
import com.thomas.pt.model.extractor.DataEndpoints
import com.thomas.pt.scoring.BusNetScoreCalculator
import com.thomas.pt.utils.Utility
import com.thomas.pt.writer.core.MATSimEventWriter
import com.thomas.pt.writer.core.WriterFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import kotlin.collections.get
import kotlin.system.exitProcess
import kotlin.time.measureTime

object Analysis: CliktCommand(name = "analysis") {
    private val logger = LoggerFactory.getLogger(javaClass)

    val yaml: Path by option("-c", "--cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("YAML configuration file")

    val matsim: Path by option("-mc", "--matsim-cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim configuration file")

    val events: File by option("-e", "--events").file(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim XML Event file")

    val output: File by option("-o", "--out").file(
        canBeDir = false
    ).required().help("Score output file")

    val format by option("-f", "--format")
        .enum<WriterFormat>()
        .default(WriterFormat.CSV)
        .help("Output file format")

    val logFile: File by option("-lf", "--log-file").file(
        canBeDir = false
    ).default(File("logs/app.log")).help("Log file")

    val signature: String by option("-sig", "--signature")
        .default(
            try { InetAddress.getLocalHost().hostName }
            catch (_: Exception) {
                "Worker-${System.currentTimeMillis()}"
            }
        )
        .help("Log signature (default: hostname)")

    val trackThroughput: Boolean by option("-wtrpt", "--write-throughput")
        .flag("-nwtrpt", "--no-write-throughput", default = false, defaultForHelp = "Disabled")
        .help("Enable channel throughput tracking")

    override fun run() {
        System.setProperty("log.file", logFile.absolutePath)
        System.setProperty("log.signature", signature)

        logger.info("Starting Analysis...")

        try {
            val metadataTime = RunMatsim.extractMetadata(yaml, matsim)
            logger.info("MATSim metadata loaded in $metadataTime")

            val (batchConfig, dataPaths) = Utility.loadYaml(yaml).let {
                it["batch_size"] as Int to
                        Utility.getYamlSubconfig(it, "files", "data")
            }

            MATSimEventWriter(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                files = DataEndpoints(
                    busPassengerDataFile = dataPaths["bus_pax_records"] as String,
                    busDelayDataFile = dataPaths["bus_delay_records"] as String,
                    tripDataFile = dataPaths["trip_records"] as String,
                    busTripDataFile = dataPaths["bus_trip_records"] as String,
                ),
                batchSize = batchConfig,
                format = format,
                trackThroughput = trackThroughput
            ).use { writer ->
                val metadata = MATSimMetadataStore.metadata
                val bus = metadata.bus.map(Id<Vehicle>::toString).toSet()
                val blacklist = metadata.blacklist.map(Id<Vehicle>::toString).toSet()
                val linkLengths = metadata.linkLength.mapKeys { it.key.toString() }

                val busPassengerExtractor = BusPassengerExtractor(bus, writer)
                val busDelayExtractor = BusDelayExtractor(bus, writer)
                val tripExtractor = TripExtractor(blacklist, writer)
                val busTripExtractor = BusTripExtractor(bus, linkLengths, writer)

                val parser = OfflineEventParser(events)

                logger.info("Parsing events from file: ${events.path}")
                val parseTime = measureTime {
                    runBlocking {
                        parser.parseEventsAsync(
                            busPassengerExtractor,
                            busDelayExtractor,
                            busTripExtractor,
                            tripExtractor
                        )
                    }
                }
                logger.info("Event parsing completed in $parseTime")
            }

            output.absoluteFile.parentFile.mkdirs()
            if (output.exists()) output.delete()

            BusNetScoreCalculator(yaml, format).calculateScore(output)
            output.setReadOnly()
        } catch (e: Exception) {
             logger.error("Analysis pipeline failed with a fatal error: ${e.message}", e)
            exitProcess(1)
        }
    }
}