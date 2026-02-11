package com.thomas.pt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.thomas.pt.core.Analysis
import com.thomas.pt.core.ArrowToCSV
import com.thomas.pt.core.SimpleMATSimRunner
import com.thomas.pt.core.Simulation

class MATSimApp: CliktCommand() {
    override fun run() = Unit
}

fun main(args: Array<String>) = MATSimApp()
    .subcommands(Simulation, Analysis, SimpleMATSimRunner, ArrowToCSV)
    .main(args)