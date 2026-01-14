package com.thomas.pt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.thomas.pt.core.RunMatsim
import com.thomas.pt.data.scoring.BusNetScoreCalculator
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.measureTime

class MATSimRunner: CliktCommand() {
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
    ).required().help("LOS output file")

    val logFile: File by option("--log-file").file(
        canBeDir = false
    ).default(File("logs/app.log")).help("Log file")

    val log: Boolean by option("--matsim-log")
        .flag(default = false, defaultForHelp = "Disabled")
        .help("Toggle MATSim logging")

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

        val context = LogManager.getContext(false) as LoggerContext
        context.reconfigure()

        logger.info("Log file redirected to: {}", logFile.path)
        logger.info("Log signature: {}", signature)

        try {
            val t1 = measureTime {
                RunMatsim.run(yaml, matsim, log)
            }
            logger.info("Run time: $t1")

            output.absoluteFile.parentFile.mkdirs()
            if (output.exists()) output.delete()

            BusNetScoreCalculator(yaml).calculateScore(output)
            output.setReadOnly()
        } catch (e: Exception) {
            logger.error("Pipeline failed with a fatal error: ${e.message}", e)
            exitProcess(1)
        }
    }
}

fun main(args: Array<String>) = MATSimRunner().main(args)