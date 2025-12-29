package com.thomas.pt.core

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.data.writer.MATSimEventWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.network.Network
import org.matsim.core.config.Config
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.Controler
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.network.NetworkUtils
import org.matsim.core.scenario.ScenarioUtils
import com.thomas.pt.event.BusDelayHandler
import com.thomas.pt.event.LinkEventHandler
import com.thomas.pt.utility.Utility
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.collections.get
import kotlin.io.path.Path

object RunMatsim {
    private val logger = LoggerFactory.getLogger(javaClass)
    fun createConfig(out: String) =
        ConfigUtils.writeConfig(ConfigUtils.createConfig(), out)

    fun loadConfig(configFile: String): Config = ConfigUtils.loadConfig(configFile)

    fun loadConfig(configFile: Path): Config = ConfigUtils.loadConfig(configFile.toString())

    fun fixNetwork(config: String, out: String? = null) =
        fixNetwork(config.substringBeforeLast("/"), loadConfig(config), out)

    // Leave out empty for in-place edit
    fun fixNetwork(parentDir: String, config: Config, out: String? = null) {
        val netFile = "$parentDir/${config.network().inputFile}"
        val currNet: Network by lazy { NetworkUtils.readNetwork(netFile, config) }
        val newNet: Network = NetworkUtils.createNetwork(config)

        currNet.nodes.values.forEach { node ->
            val id = node.id
            if ("^n.+".toRegex().matches(id.toString()))
                newNet.addNode(node)
            else
                NetworkUtils.createAndAddNode(newNet, Id.createNodeId("n$id"), node.coord)
        }

        val newNodes = newNet.nodes
        currNet.links.values.forEach { link ->
            val id = link.id
            val patterns = listOf("^l.+".toRegex(), "^ln.+r$".toRegex())
            if (patterns.any { it.matches(id.toString()) })
                newNet.addLink(link)
            else {
                val fromNodes = newNodes.values.first { node ->
                    node.id.toString() == "n${link.fromNode.id}"
                }
                val toNodes = newNodes.values.first { node ->
                    node.id.toString() == "n${link.toNode.id}"
                }
                val newId = Id.createLinkId(if (fromNodes == toNodes) "ln${id}r" else "l$id")
                newNet.addLink(
                    NetworkUtils.createLink(newId, fromNodes, toNodes, newNet,
                        link.length, link.freespeed, link.capacity, link.numberOfLanes
                    ).apply { allowedModes = setOf("car", "pt") }
                )
            }
        }

        NetworkUtils.writeNetwork(newNet, out ?: netFile)
    }

    fun run(configPath: Path, matsimConfig: Path)
        = run(Utility.loadYaml(configPath), matsimConfig)

    fun run(yamlConfig: Map<String, Any>, matsimConfigPath: Path) {
        logger.info("Running MATSim core module")

        val matsimConfig = loadConfig(matsimConfigPath)

        matsimConfig.controller().overwriteFileSetting = OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists
        matsimConfig.controller().apply {
            val parentDir = matsimConfigPath.toAbsolutePath().normalize().parent
            outputDirectory = "${parentDir.resolve(outputDirectory)}"
        }

        matsimConfig.qsim().isUseLanes = true

        val scenario: Scenario = ScenarioUtils.loadScenario(matsimConfig)
        val controller = Controler(scenario)

        MATSimMetadataStore.build(
            yamlConfig = yamlConfig,
            net = controller.scenario.network,
            schedule = controller.scenario.transitSchedule,
            transitVehicles = controller.scenario.transitVehicles,
        )

//        val lastIteration = matsimConfig.controller().lastIteration
//
//        val arrowBatchConfig = Utility.getYamlSubconfig(yamlConfig, "arrow").let{
//            it["batch_size"] as Int
//        }
//
//        val dataOutConfig = Utility.getYamlSubconfig(yamlConfig, "files", "data")
//        val linkDataOut = Path(dataOutConfig["link_records"] as String).apply {
//            parent.toFile().mkdirs()
//        }
//        val busDelayDataOut = Path(dataOutConfig["stop_records"] as String).apply {
//            parent.toFile().mkdirs()
//        }
//        MATSimEventWriter(
//            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
//            batchSize = arrowBatchConfig,
//            linkDataPath = linkDataOut,
//            busDelayDataPath = busDelayDataOut
//        ).use { eventWriter ->
//            val linkHandler = LinkEventHandler(
//                targetIter = lastIteration,
//                eventWriter = eventWriter
//            )
//
//            val busDelayHandler = BusDelayHandler(
//                targetIter = lastIteration,
//                eventWriter = eventWriter
//            )
//
//            controller.addOverridingModule(
//                object: AbstractModule() {
//                    override fun install() {
//                        this.addEventHandlerBinding().toInstance(linkHandler)
//                        this.addEventHandlerBinding().toInstance(busDelayHandler)
//                    }
//                }
//            )
//
//            controller.run()
//        }
    }
}