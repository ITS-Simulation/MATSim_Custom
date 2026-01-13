package com.thomas.pt.core

import com.thomas.pt.event.QDelayHandler
import com.thomas.pt.event.QBusPassagerHandler
import com.thomas.pt.event.QTripHandler
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


fun main(){

    val config = ConfigUtils.loadConfig("QTesst/data_cottbus/config01.xml")
    val scenario = ScenarioUtils.loadScenario(config)
    val controller = Controler(scenario)
    val lastIter = config.controller().lastIteration

    // Change Data -> Handler
    val BPhandler = QBusPassagerHandler(lastIter)
    val Dhandler = QDelayHandler(lastIter)
    val Thandler = QTripHandler(lastIter)

    // 4. Custom Module
    controller.addOverridingModule(object : AbstractModule() {
        override fun install() {
            this.addEventHandlerBinding().toInstance(BPhandler);
            this.addEventHandlerBinding().toInstance(Dhandler);
            this.addEventHandlerBinding().toInstance(Thandler);

        }
    })

    controller.run()
}