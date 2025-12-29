package com.thomas.pt.event

import com.thomas.pt.data.metadata.MATSimMetadataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.LinkEnterEvent
import org.matsim.api.core.v01.events.LinkLeaveEvent
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent
import org.matsim.api.core.v01.events.TransitDriverStartsEvent
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler
import org.matsim.api.core.v01.network.Link
import org.matsim.vehicles.Vehicle
import com.thomas.pt.data.model.extractor.LinkData
import com.thomas.pt.data.writer.MATSimEventWriter

/**
 * Tracks vehicle link traversals and outputs link_records.
 * 
 * Output schema (link_records):
 * - vehicle_id, link_id, line_id, enter_time, exit_time, travel_distance, passenger_load, is_bus
 */
class LinkEventHandler(
    private val targetIter: Int,
    private val eventWriter: MATSimEventWriter
) :
    LinkEnterEventHandler,
    LinkLeaveEventHandler,
    VehicleEntersTrafficEventHandler,
    VehicleLeavesTrafficEventHandler,
    TransitDriverStartsEventHandler,
    PersonEntersVehicleEventHandler,
    PersonLeavesVehicleEventHandler
{
    private val tripMap = mutableMapOf<Id<Vehicle>, Int>()

    private data class VehicleState(
        var currentLink: Id<Link>? = null,
        var enterTime: Double? = null,
        var lineId: String? = null,
        var passengerCount: Int = 0,
        var isBus: Boolean = false
    )

    private val metadata by lazy { MATSimMetadataStore.metadata }

    private val vehicleStates = mutableMapOf<Id<Vehicle>, VehicleState>()

    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()

    private fun getOrCreateState(vehicleId: Id<Vehicle>): VehicleState
        = vehicleStates.getOrPut(vehicleId) {
            VehicleState(isBus = vehicleId in metadata.bus)
        }

    override fun handleEvent(event: TransitDriverStartsEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        if (vehicleId in metadata.blacklist) return

        val state = getOrCreateState(vehicleId)
        state.lineId = event.transitLineId.toString()
        state.isBus = vehicleId in metadata.bus
    }

    override fun handleEvent(event: VehicleEntersTrafficEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        if (vehicleId in metadata.blacklist) return

        val state = getOrCreateState(vehicleId)
        state.currentLink = event.linkId
        state.enterTime = event.time
        tripMap[vehicleId] = (tripMap[vehicleId] ?: 0) + 1
    }

    override fun handleEvent(event: LinkEnterEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        if (vehicleId in metadata.blacklist) return

        val state = getOrCreateState(vehicleId)
        state.currentLink = event.linkId
        state.enterTime = event.time
    }

    override fun handleEvent(event: LinkLeaveEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        val state = vehicleStates[vehicleId] ?: return
        val linkId = state.currentLink ?: return
        val enterTime = state.enterTime ?: return

        val linkMeta = metadata.linkData[linkId] ?: return
        val travelDistance = linkMeta.length

        val duration = event.time - enterTime
        if (duration < 1.0) return

        assert(
            eventWriter.pushLinkData(
                LinkData(
                    vehicleId = vehicleId.toString(),
                    linkId = linkId.toString(),
                    lineId = state.lineId,
                    tripId = tripMap[vehicleId]!!,
                    enterTime = enterTime,
                    exitTime = event.time,
                    travelDistance = travelDistance,
                    passengerLoad = state.passengerCount.takeIf { state.isBus },
                    isBus = state.isBus
                )
            )
        )

        state.currentLink = null
        state.enterTime = null
    }

    override fun handleEvent(event: VehicleLeavesTrafficEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        val state = vehicleStates[vehicleId] ?: return
        val linkId = state.currentLink
        val enterTime = state.enterTime

        if (linkId != null && enterTime != null) {
            val linkMeta = metadata.linkData[linkId]
            if (linkMeta != null) {
                val duration = event.time - enterTime
                if (duration >= 1.0) {
                    // TODO: Implement async writing to avoid blocking event handling
                    eventWriter.pushLinkData(
                        LinkData(
                            vehicleId = vehicleId.toString(),
                            linkId = linkId.toString(),
                            lineId = state.lineId,
                            tripId = tripMap[vehicleId]!!,
                            enterTime = enterTime,
                            exitTime = event.time,
                            travelDistance = linkMeta.length,
                            passengerLoad = state.passengerCount.takeIf { state.isBus },
                            isBus = state.isBus
                        )
                    )
                }
            }
        }

        vehicleStates.remove(vehicleId)
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!startCollect.value) return

        if (event.personId.toString().startsWith("pt_")) return

        val state = vehicleStates[event.vehicleId] ?: return
        state.passengerCount++
    }

    override fun handleEvent(event: PersonLeavesVehicleEvent) {
        if (!startCollect.value) return

        if (event.personId.toString().startsWith("pt_")) return

        val state = vehicleStates[event.vehicleId] ?: return
        state.passengerCount--
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        vehicleStates.clear()
    }
}