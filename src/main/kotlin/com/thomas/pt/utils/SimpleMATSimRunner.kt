package com.thomas.pt.utils

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.thomas.pt.core.RunMatsim

@Suppress("unused")
class SimpleMATSimRunner: CliktCommand() {
    val config by option("--matsim-cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim config file")

    override fun run() = RunMatsim.simpleRun(config)
}

//fun main(args: Array<String>) = SimpleMATSimRunner().main(args)