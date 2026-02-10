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
import com.thomas.pt.scoring.BusNetScoreCalculator
import com.thomas.pt.writer.core.WriterFormat
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.measureTime

object Simulation: CliktCommand(name = "sim") {
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

    val score: File by option("-s", "--score").file(
        canBeDir = false
    ).required().help("Score output file")

    val scoreRecords: File? by option("-sc", "--score-records").file(
        canBeDir = false
    ).help("Score records output file")

    val logFile: File by option("-lf", "--log-file").file(
        canBeDir = false
    ).default(File("logs/app.log")).help("Log file")

    val log: Boolean by option("-msl", "--matsim-log")
        .flag("--no-matsim-log", default = false, defaultForHelp = "Disabled")
        .help("Toggle MATSim logging")

    val signature: String by option("-sig", "--signature")
        .default(
            try { InetAddress.getLocalHost().hostName }
            catch (_: Exception) {
                "Worker-${System.currentTimeMillis()}"
            }
        )
        .help("Log signature (default: hostname)")

    val format by option("-f", "--format")
        .enum<WriterFormat>()
        .default(WriterFormat.ARROW)
        .help("Output file format")

    val trackThroughput: Boolean by option("-wtrpt", "--write-throughput")
        .flag("-nwtrpt", "--no-write-throughput", default = false, defaultForHelp = "Disabled")
        .help("Enable channel throughput tracking")

    override fun run() {
        System.setProperty("log.file", logFile.absolutePath)
        System.setProperty("log.signature", signature)

        val context = LogManager.getContext(false) as LoggerContext
        context.reconfigure()

        logger.info("Log file redirected to: {}", logFile.path)
        logger.info("Log signature: {}", signature)

        try {
            val t1 = measureTime {
                RunMatsim.run(yaml, matsim, log, format, trackThroughput)
            }
            logger.info("Run time: $t1")

            score.absoluteFile.parentFile.mkdirs()
            if (score.exists()) score.delete()

            BusNetScoreCalculator(yaml, format).calculateScore(score, scoreRecords)
            score.setReadOnly()
        } catch (e: Exception) {
            logger.error("Pipeline failed with a fatal error: ${e.message}", e)
            exitProcess(1)
        }
    }
}
