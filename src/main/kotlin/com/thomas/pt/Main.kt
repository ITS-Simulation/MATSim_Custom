package com.thomas.pt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.thomas.pt.core.RunMatsim
import com.thomas.pt.data.processor.MATSimProcessor
import java.io.File
import java.nio.file.Path
import kotlin.time.measureTime

class MATSimRunner: CliktCommand() {
    val yaml: Path by option().path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("YAML configuration file")

    val matsim: Path by option().path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim configuration file")

    val output: File by option().file(
        canBeDir = false
    ).required().help("Output directory for results")

    override fun run() {
        val t1 = measureTime {
            RunMatsim.run(yaml, matsim)
        }
        println("Run time: $t1")

        val processor = MATSimProcessor(yaml)
//        processor.processAvgTripLength().let {
//            output.apply {
//                parentFile.mkdirs()
//                writeText(it.toString())
//            }
//        }
    }
}

fun main(args: Array<String>) = MATSimRunner().main(args)
