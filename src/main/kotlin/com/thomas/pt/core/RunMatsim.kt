package com.thomas.pt.core

import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.writer.core.MATSimEventWriter
import com.thomas.pt.extractor.online.BusDelayHandler
import com.thomas.pt.extractor.online.BusPassengerHandler
import com.thomas.pt.extractor.online.BusTripHandler
import com.thomas.pt.extractor.online.TripHandler
import com.thomas.pt.model.extractor.DataEndpoints
import com.thomas.pt.utils.Utility
import com.thomas.pt.writer.core.WriterFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.config.ConfigUtils
import org.matsim.core.config.groups.ControllerConfigGroup
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.Controler
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.scenario.ScenarioUtils
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.collections.get
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

object RunMatsim {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun extractMetadata(
        yaml: Path,
        matsim: Path,
    ): Duration {
        val config = loadConfig(matsim)
        val scenario = ScenarioUtils.loadScenario(config)
        return extractMetadata(
            yamlConfig = Utility.loadYaml(yaml),
            scenario = scenario
        )
    }

    private fun extractMetadata(
        yamlConfig: Map<String, Any>,
        scenario: Scenario
    ): Duration =
        measureTime {
            MATSimMetadataStore.build(
                yamlConfig = yamlConfig,
                net = scenario.network,
                plan = scenario.population,
                schedule = scenario.transitSchedule,
                transitVehicles = scenario.transitVehicles,
            )
        }

    @Suppress("unused")
    fun createConfig(out: String) = ConfigUtils.writeConfig(ConfigUtils.createConfig(), out)

    fun loadConfig(configFile: Path): Config = ConfigUtils.loadConfig(configFile.toString())

    fun simpleRun(configPath: Path) {
        logger.info("Running simple MATSim executor module")
        val time = measureTime {
            val config = loadConfig(configPath)

            config.controller().apply {
                overwriteFileSetting = OverwriteFileSetting.deleteDirectoryIfExists

                val parentDir = configPath.toAbsolutePath().normalize().parent
                outputDirectory = "${parentDir.resolve(outputDirectory)}"
            }

            val scenario = ScenarioUtils.loadScenario(config)
            Controler(scenario).run()
        }
        logger.info("MATSim run completed in $time")
    }

    fun run(configPath: Path, matsimConfig: Path, log: Boolean, format: WriterFormat, trackThroughput: Boolean)
        = run(Utility.loadYaml(configPath), matsimConfig, log, format, trackThroughput)

    fun run(yamlConfig: Map<String, Any>, matsimConfigPath: Path, log: Boolean, format: WriterFormat, trackThroughput: Boolean) {
        logger.info("Running MATSim core module")

        val (matsim, loadTime) = measureTimedValue {
            val matsimConfig = loadConfig(matsimConfigPath)
            val lastIteration = matsimConfig.controller().lastIteration

            matsimConfig.controller().apply {
                overwriteFileSetting = OverwriteFileSetting.deleteDirectoryIfExists

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

            ScenarioUtils.loadScenario(matsimConfig) to lastIteration
        }
        logger.info("MATSim scenario loaded in $loadTime")
        val (scenario, lastIteration) = matsim

        val metadataTime = extractMetadata(yamlConfig, scenario)
        logger.info("MATSim metadata loaded in $metadataTime")

        val batchConfig = yamlConfig["batch_size"] as Int
        val dataOutConfig = Utility.getYamlSubconfig(yamlConfig, "files", "data")

        MATSimEventWriter(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            batchSize = batchConfig,
            files = DataEndpoints(
                busPassengerDataFile = dataOutConfig["bus_pax_records"] as String,
                busDelayDataFile = dataOutConfig["bus_delay_records"] as String,
                tripDataFile = dataOutConfig["trip_records"] as String,
                busTripDataFile = dataOutConfig["bus_trip_records"] as String,
            ),
            format = format,
            trackThroughput = trackThroughput
        ).use { writer ->
            val controller = Controler(scenario)
            val busPassengerHandler = BusPassengerHandler(lastIteration, writer)
            val busDelayHandler = BusDelayHandler(lastIteration, writer)
            val busTripHandler = BusTripHandler(lastIteration, writer)
            val tripHandler = TripHandler(lastIteration, writer)

            controller.addOverridingModule(
                object: AbstractModule() {
                    override fun install() {
                        this.addEventHandlerBinding().toInstance(busPassengerHandler)
                        this.addEventHandlerBinding().toInstance(busDelayHandler)
                        this.addEventHandlerBinding().toInstance(busTripHandler)
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