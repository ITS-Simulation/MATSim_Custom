package com.thomas.pt.event

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.data.model.extractor.BusPassengerData
import com.thomas.pt.data.writer.MATSimEventWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

class BusPassengerHandler(
    private val targetIter: Int,
    private val eventWriter: MATSimEventWriter
) :
    TransitDriverStartsEventHandler,
    PersonEntersVehicleEventHandler,
    VehicleLeavesTrafficEventHandler
{
    private val metadata by lazy { MATSimMetadataStore.metadata }
    private var vehDriverBusMap =  mutableMapOf<Id<Vehicle>,Id<Person>>()
    
    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()

    override fun handleEvent(event: TransitDriverStartsEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in metadata.bus) return

        vehDriverBusMap[event.vehicleId] = event.driverId
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in vehDriverBusMap.keys) return
        if (event.personId == vehDriverBusMap[event.vehicleId]) return
        
        assert(
            eventWriter.pushBusPassengerData(
                BusPassengerData(
                    personId = event.personId.toString(),
                    busId = event.vehicleId.toString(),
                )
            )
        )
    }

    override fun handleEvent(event: VehicleLeavesTrafficEvent) {
        if (!startCollect.value) return
        vehDriverBusMap.remove(event.vehicleId)
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        vehDriverBusMap.clear()
    }
}