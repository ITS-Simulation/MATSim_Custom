package com.thomas.pt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.thomas.pt.core.RunMatsim
import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.extractor.offline.BusDelayExtractor
import com.thomas.pt.extractor.offline.BusPassengerExtractor
import com.thomas.pt.extractor.offline.TripExtractor
import com.thomas.pt.extractor.offline.model.OfflineEventParser
import com.thomas.pt.scoring.BusNetScoreCalculator
import com.thomas.pt.utils.Utility
import com.thomas.pt.writer.core.MATSimEventWriter
import com.thomas.pt.writer.core.WriterFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.measureTime

class MATSimApp: CliktCommand() {
    override fun run() = Unit
}

class Simulation: CliktCommand(name = "sim") {
    private val logger = LoggerFactory.getLogger(javaClass)

    val yaml: Path by option("--cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("YAML configuration file")

    val matsim: Path by option("--matsim-cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim configuration file")

    val output: File by option("--out").file(
        canBeDir = false
    ).required().help("Score output file")

    val logFile: File by option("--log-file").file(
        canBeDir = false
    ).default(File("logs/app.log")).help("Log file")

    val log: Boolean by option("--matsim-log")
        .flag("--no-matsim-log", default = false, defaultForHelp = "Disabled")
        .help("Toggle MATSim logging")

    val signature: String by option("--signature")
        .default(
            try { InetAddress.getLocalHost().hostName }
            catch (_: Exception) {
                "Worker-${System.currentTimeMillis()}"
            }
        )
        .help("Log signature (default: hostname)")

    val format by option("--format")
        .enum<WriterFormat>()
        .default(WriterFormat.ARROW)
        .help("Output file format")

    override fun run() {
        System.setProperty("log.file", logFile.absolutePath)
        System.setProperty("log.signature", signature)

        val context = LogManager.getContext(false) as LoggerContext
        context.reconfigure()

        logger.info("Log file redirected to: {}", logFile.path)
        logger.info("Log signature: {}", signature)

        try {
            val t1 = measureTime {
                RunMatsim.run(yaml, matsim, log, format)
            }
            logger.info("Run time: $t1")

            output.absoluteFile.parentFile.mkdirs()
            if (output.exists()) output.delete()

            BusNetScoreCalculator(yaml, format).calculateScore(output)
            output.setReadOnly()
        } catch (e: Exception) {
            logger.error("Pipeline failed with a fatal error: ${e.message}", e)
            exitProcess(1)
        }
    }
}

class Analysis: CliktCommand(name = "analysis") {
    private val logger = LoggerFactory.getLogger(javaClass)

    val yaml: Path by option("--cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("YAML configuration file")

    val matsim: Path by option("--matsim-cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim configuration file")

    val events: File by option("--events").file(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim XML Event file")

    val output: File by option("--out").file(
        canBeDir = false
    ).required().help("Score output file")

    val format by option("--format")
        .enum<WriterFormat>()
        .default(WriterFormat.CSV)
        .help("Output file format")

    val logFile: File by option("--log-file").file(
        canBeDir = false
    ).default(File("logs/app.log")).help("Log file")

    val signature: String by option("--signature")
        .default(
            try { InetAddress.getLocalHost().hostName }
            catch (_: Exception) {
                "Worker-${System.currentTimeMillis()}"
            }
        )
        .help("Log signature (default: hostname)")

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
                busPaxData = dataPaths["bus_pax_records"] as String,
                busDelayData = dataPaths["bus_delay_records"] as String,
                tripData = dataPaths["trip_records"] as String,
                batchSize = batchConfig,
                format = format,
            ).use { writer ->
                val metadata = MATSimMetadataStore.metadata
                val bus = metadata.bus.map(Id<Vehicle>::toString).toSet()
                val blacklist = metadata.blacklist.map(Id<Vehicle>::toString).toSet()

                val busPassengerExtractor = BusPassengerExtractor(bus, writer)
                val busDelayExtractor = BusDelayExtractor(bus, writer)
                val tripExtractor = TripExtractor(blacklist, writer)

                val parser = OfflineEventParser(events)

                logger.info("Parsing events from file: ${events.path}")
                val parseTime = measureTime {
                    runBlocking {
                        parser.parseEventsAsync(
                            busPassengerExtractor,
                            busDelayExtractor,
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

fun main(args: Array<String>) = MATSimApp()
    .subcommands(Simulation(), Analysis())
    .main(args)