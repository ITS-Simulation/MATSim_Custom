package com.thomas.pt.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path

object SimpleMATSimRunner: CliktCommand(name = "simple-run") {
    val config by option("--matsim-cfg").path(
        mustExist = true,
        mustBeReadable = true,
        canBeDir = false
    ).required().help("MATSim config file")

    override fun run() = RunMatsim.simpleRun(config)
}