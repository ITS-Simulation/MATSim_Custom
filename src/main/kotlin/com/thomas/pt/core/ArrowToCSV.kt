package com.thomas.pt.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.io.readArrowIPC
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.io.File
import java.net.InetAddress

object ArrowToCSV: CliktCommand(name = "arrow") {
    val file by option("-f", "--file").file(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("Arrow dataset file")

    val output by option("-o", "--output").file(
        canBeDir = false
    ).help("Output CSV file")

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
        DataFrame.readArrowIPC(file.inputStream()).writeCsv(
            output ?: File(file.absolutePath
                .replaceAfterLast(".", "csv")
            )
        )
    }
}