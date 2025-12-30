package com.thomas.pt.core

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.data.writer.MATSimEventWriter
import com.thomas.pt.event.BusDelayHandler
import com.thomas.pt.event.LinkEventHandler
import com.thomas.pt.utility.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.config.ConfigUtils
import org.matsim.core.config.groups.ControllerConfigGroup
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.Controler
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.scenario.ScenarioUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path

object RunMatsim {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Suppress("unused")
    fun createConfig(out: String) = ConfigUtils.writeConfig(ConfigUtils.createConfig(), out)

    fun loadConfig(configFile: Path): Config = ConfigUtils.loadConfig(configFile.toString())

    fun run(configPath: Path, matsimConfig: Path, log: Boolean)
        = run(Utility.loadYaml(configPath), matsimConfig, log)

    fun run(yamlConfig: Map<String, Any>, matsimConfigPath: Path, log: Boolean) {
        logger.info("Running MATSim core module")

        val matsimConfig = loadConfig(matsimConfigPath)

        matsimConfig.controller().overwriteFileSetting = OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists
        matsimConfig.controller().apply {
            val parentDir = matsimConfigPath.toAbsolutePath().normalize().parent
            outputDirectory = "${parentDir.resolve(outputDirectory)}"
        }

        matsimConfig.qsim().isUseLanes = true

        if (!log) {
            matsimConfig.controller().apply {
                writeEventsInterval = 0
                writePlansInterval = 0
                writeTripsInterval = 0
                writeSnapshotsInterval = 0
                createGraphsInterval = 0
                legDurationsInterval = 0
                legHistogramInterval = 0
                dumpDataAtEnd = false
                cleanItersAtEnd = ControllerConfigGroup.CleanIterations.delete
            }
        }

        val scenario: Scenario = ScenarioUtils.loadScenario(matsimConfig)
        val controller = Controler(scenario)

        MATSimMetadataStore.build(
            yamlConfig = yamlConfig,
            net = controller.scenario.network,
            schedule = controller.scenario.transitSchedule,
            transitVehicles = controller.scenario.transitVehicles,
        )

        val lastIteration = matsimConfig.controller().lastIteration

        val arrowBatchConfig = Utility.getYamlSubconfig(yamlConfig, "arrow").let{
            it["batch_size"] as Int
        }

        val dataOutConfig = Utility.getYamlSubconfig(yamlConfig, "files", "data")
        val linkDataOut = File(dataOutConfig["link_records"] as String).apply {
            parentFile.mkdirs()
        }
        val busDelayDataOut = File(dataOutConfig["stop_records"] as String).apply {
            parentFile.mkdirs()
        }
        MATSimEventWriter(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            batchSize = arrowBatchConfig,
            linkDataPath = linkDataOut,
            busDelayDataPath = busDelayDataOut
        ).use { eventWriter ->
            val linkHandler = LinkEventHandler(
                targetIter = lastIteration,
                eventWriter = eventWriter
            )

            val busDelayHandler = BusDelayHandler(
                targetIter = lastIteration,
                eventWriter = eventWriter
            )

            controller.addOverridingModule(
                object: AbstractModule() {
                    override fun install() {
                        this.addEventHandlerBinding().toInstance(linkHandler)
                        this.addEventHandlerBinding().toInstance(busDelayHandler)
                    }
                }
            )

            val originalOut = System.out
            if (!log) System.setOut(PrintStream(OutputStream.nullOutputStream()))
            try { controller.run() }
            finally { if (!log) System.setOut(originalOut) }
        }
    }
}