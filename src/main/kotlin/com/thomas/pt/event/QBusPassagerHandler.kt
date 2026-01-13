package com.thomas.pt.event

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.events.TransitDriverStartsEvent
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler
import org.matsim.vehicles.Vehicle
import org.matsim.api.core.v01.population.Person
import kotlin.collections.mutableMapOf

/**

 * Output schema (link_records):
 * - person_id, vehicle_id
 */
class QBusPassagerHandler(
    private val targetIter: Int
    ) :
        TransitDriverStartsEventHandler,
        PersonEntersVehicleEventHandler,
        VehicleLeavesTrafficEventHandler
    {
    private var driverVehBusMap =  mutableMapOf<Id<Person>,Id<Vehicle>>()
    private var vehDriverBusMap =  mutableMapOf<Id<Vehicle>,Id<Person>>()
    private var isCollecting = false

    override fun handleEvent(event: TransitDriverStartsEvent) {
        if (!isCollecting) return

        driverVehBusMap[event.driverId] = event.vehicleId
        vehDriverBusMap[ event.vehicleId] = event.driverId
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        // if not target interation
        if (!isCollecting) return
        //if vehicle is not pt
        if (event.vehicleId !in vehDriverBusMap.keys) return
        //if person boarding is driver
        if (event.personId == vehDriverBusMap[event.vehicleId]) return
        
        // Debug: Write to hardcoded CSV
        java.io.File("QTesst/debug/debug_QBusPassagerHandler.csv").appendText("${event.personId},${event.vehicleId}\n")
    }

    override fun handleEvent(event: VehicleLeavesTrafficEvent) {
        if (!isCollecting) return
        //if vehicle leave traffic, delete key-value in map
        vehDriverBusMap.remove(event.vehicleId)
        driverVehBusMap.remove(event.personId)
    }

    override fun reset(iteration: Int) {
        isCollecting = (iteration == targetIter)
        if(isCollecting) {
            java.io.File("QTesst/debug/debug_QBusPassagerHandler.csv").appendText("personId,vehicleId\n")
        }
        vehDriverBusMap.clear()
        driverVehBusMap.clear()

        println("QBusPassagerHandler: Iteration $iteration started. Collecting? $isCollecting")
    }
}