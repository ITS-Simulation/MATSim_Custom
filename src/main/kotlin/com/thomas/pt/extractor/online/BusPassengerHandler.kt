package com.thomas.pt.extractor.online

import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.model.data.BusPassengerData
import com.thomas.pt.writer.core.MATSimEventWriter
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
    private val writer: MATSimEventWriter
) :
    TransitDriverStartsEventHandler,
    PersonEntersVehicleEventHandler,
    VehicleLeavesTrafficEventHandler
{
    private val metadata by lazy { MATSimMetadataStore.metadata }
    private var vehDriverMap = mutableMapOf<Id<Vehicle>, Id<Person>>()

    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()

    override fun handleEvent(event: TransitDriverStartsEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in metadata.bus) return

        vehDriverMap[event.vehicleId] = event.driverId
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in vehDriverMap.keys) return
        if (event.personId == vehDriverMap[event.vehicleId]) return
        
        require(
            writer.pushBusPassengerData(
                BusPassengerData(
                    personId = event.personId.toString(),
                    busId = event.vehicleId.toString(),
                )
            )
        )
        writer.recordThroughput(event.time, "BusPassengerData")
    }

    override fun handleEvent(event: VehicleLeavesTrafficEvent) {
        if (!startCollect.value) return
        vehDriverMap.remove(event.vehicleId)
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        vehDriverMap.clear()
    }
}