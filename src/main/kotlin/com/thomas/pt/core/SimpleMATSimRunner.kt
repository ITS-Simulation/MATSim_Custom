package com.thomas.pt.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.net.InetAddress

object SimpleMATSimRunner: CliktCommand(name = "simple-run") {
    val config by option("-mc", "--matsim-cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim config file")

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

    override fun run() {
        System.setProperty("log.file", logFile.absolutePath)
        System.setProperty("log.signature", signature)
        RunMatsim.simpleRun(config)
    }
}