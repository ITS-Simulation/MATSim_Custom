package com.thomas.pt.extractor.online

import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.model.data.BusDelayData
import com.thomas.pt.writer.core.MATSimEventWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler

class BusDelayHandler(
    private val targetIter: Int,
    private val writer: MATSimEventWriter
) :
    VehicleArrivesAtFacilityEventHandler,
    VehicleDepartsAtFacilityEventHandler 
{
    private val metadata by lazy { MATSimMetadataStore.metadata }
    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()
    private val busMap = mutableMapOf<Id<Vehicle>, BusDelayData>()
    
    override fun handleEvent(event: VehicleArrivesAtFacilityEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in metadata.bus) return

        busMap[event.vehicleId] = BusDelayData(
            stopId = event.facilityId.toString(),
            arrDelay = event.delay,
            depDelay = 0.0,
        )
    }

    override fun handleEvent(event: VehicleDepartsAtFacilityEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in busMap) return

        require(
            writer.pushBusDelayData(
                busMap[event.vehicleId]!!.copy(
                    depDelay = event.delay,
                )
            )
        )
        writer.recordThroughput(event.time, "BusDelayData")

        busMap.remove(event.vehicleId)
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        busMap.clear()
    }
}