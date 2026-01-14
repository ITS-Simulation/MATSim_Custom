package com.thomas.pt.core

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.data.metadata.NetworkBoundaries
import com.thomas.pt.data.writer.MATSimEventWriter
import com.thomas.pt.event.BusDelayHandler
import com.thomas.pt.event.BusPassengerHandler
import com.thomas.pt.event.TripHandler
import com.thomas.pt.utility.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.matsim.api.core.v01.network.Network
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
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

object RunMatsim {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun getNetworkBoundary(net: Network): NetworkBoundaries
        = net.nodes.values.let {
            NetworkBoundaries(
                minX = it.minOf { node -> node.coord.x },
                minY = it.minOf { node -> node.coord.y },
                maxX = it.maxOf { node -> node.coord.x },
                maxY = it.maxOf { node -> node.coord.y },
            )
        }

    @Suppress("unused")
    fun createConfig(out: String) = ConfigUtils.writeConfig(ConfigUtils.createConfig(), out)

    fun loadConfig(configFile: Path): Config = ConfigUtils.loadConfig(configFile.toString())

    fun run(configPath: Path, matsimConfig: Path, log: Boolean)
        = run(Utility.loadYaml(configPath), matsimConfig, log)

    fun run(yamlConfig: Map<String, Any>, matsimConfigPath: Path, log: Boolean) {
        logger.info("Running MATSim core module")

        val (matsim, loadTime) = measureTimedValue {
            val matsimConfig = loadConfig(matsimConfigPath)
            val lastIteration = matsimConfig.controller().lastIteration

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

            Controler(ScenarioUtils.loadScenario(matsimConfig)) to lastIteration
        }
        logger.info("MATSim scenario loaded in $loadTime")
        val (controller, lastIteration) = matsim

        val metadataTime = measureTime {
            MATSimMetadataStore.build(
                yamlConfig = yamlConfig,
                netBound = getNetworkBoundary(controller.scenario.network),
                plan = controller.scenario.population,
                schedule = controller.scenario.transitSchedule,
                transitVehicles = controller.scenario.transitVehicles,
            )
        }
        logger.info("MATSim metadata loaded in $metadataTime")

        val arrowBatchConfig = Utility.getYamlSubconfig(yamlConfig, "arrow").let{
            it["batch_size"] as Int
        }

        val dataOutConfig = Utility.getYamlSubconfig(yamlConfig, "files", "data")
        val busPassengerDataOut = File(dataOutConfig["bus_pax_records"] as String)
        val busDelayDataOut = File(dataOutConfig["bus_delay_records"] as String)
        val tripDataOut = File(dataOutConfig["trip_records"] as String)

        MATSimEventWriter(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            batchSize = arrowBatchConfig,
            busPaxDataFile = busPassengerDataOut,
            busDelayDataFile = busDelayDataOut,
            tripDataFile = tripDataOut,
        ).use { eventWriter ->
            val busPassengerHandler = BusPassengerHandler(
                targetIter = lastIteration,
                eventWriter = eventWriter,
            )

            val busDelayHandler = BusDelayHandler(
                targetIter = lastIteration,
                eventWriter = eventWriter,
            )

            val tripHandler = TripHandler(
                targetIter = lastIteration,
                eventWriter = eventWriter,
            )

            controller.addOverridingModule(
                object: AbstractModule() {
                    override fun install() {
                        this.addEventHandlerBinding().toInstance(busPassengerHandler)
                        this.addEventHandlerBinding().toInstance(busDelayHandler)
                        this.addEventHandlerBinding().toInstance(tripHandler)
                    }
                }
            )

            val originalOut = System.out
            if (!log) System.setOut(PrintStream(OutputStream.nullOutputStream()))
            try {
                val runTime = measureTime { controller.run() }
                logger.info("MATSim run completed in $runTime")
                logger.info("Total execution time: ${loadTime + metadataTime + runTime}")
            }
            finally { if (!log) System.setOut(originalOut) }
        }
    }
}