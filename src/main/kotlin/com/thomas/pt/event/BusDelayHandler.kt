package com.thomas.pt.event

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.data.model.extractor.BusDelayData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.LinkEnterEvent
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent
import org.matsim.api.core.v01.events.TransitDriverStartsEvent
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler
import org.matsim.api.core.v01.network.Link
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler
import org.matsim.pt.transitSchedule.api.TransitLine
import org.matsim.vehicles.Vehicle
import com.thomas.pt.data.writer.MATSimEventWriter

/**
 * Tracks bus stop arrivals/departures and outputs stop_records.
 * 
 * Output schema (stop_records):
 * - vehicle_id, stop_id, link_id, line_id, timestamp, schedule_deviation, scheduled_headway, boarding, alighting
 */
class BusDelayHandler(
    private val targetIter: Int,
    private val eventWriter: MATSimEventWriter
) :
    LinkEnterEventHandler,
    TransitDriverStartsEventHandler,
    VehicleArrivesAtFacilityEventHandler,
    VehicleDepartsAtFacilityEventHandler,
    PersonEntersVehicleEventHandler,
    PersonLeavesVehicleEventHandler
{
    private data class StopState(
        var currentLink: Id<Link>? = null,
        var lineId: Id<TransitLine>? = null,
        var stopId: String? = null,
        var arrivalTime: Double? = null,
        var boarding: Int = 0,
        var alighting: Int = 0,
        var delay: Double = -1.0,
    )

    private val metadata by lazy { MATSimMetadataStore.metadata }

    private val vehicleStates = mutableMapOf<Id<Vehicle>, StopState>()

    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()

    private fun getOrCreateState(vehicleId: Id<Vehicle>): StopState? {
        if (vehicleId !in metadata.bus) return null
        return vehicleStates.getOrPut(vehicleId) { StopState() }
    }

    override fun handleEvent(event: TransitDriverStartsEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        val state = getOrCreateState(vehicleId) ?: return
        state.lineId = event.transitLineId
    }

    override fun handleEvent(event: LinkEnterEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        val state = getOrCreateState(vehicleId) ?: return
        state.currentLink = event.linkId
    }

    override fun handleEvent(event: VehicleArrivesAtFacilityEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        val state = vehicleStates[vehicleId] ?: return

        state.stopId = event.facilityId.toString()
        state.currentLink = state.currentLink ?: Id.createLinkId("undefined")
        state.arrivalTime = event.time
        state.boarding = 0
        state.alighting = 0
        state.delay = event.delay
    }

    override fun handleEvent(event: VehicleDepartsAtFacilityEvent) {
        if (!startCollect.value) return
        val vehicleId = event.vehicleId

        val state = vehicleStates[vehicleId] ?: return
        val stopId = state.stopId ?: return
        val arrivalTime = state.arrivalTime ?: return
        val lineId = state.lineId ?: return
        val linkId = state.currentLink ?: return

        val headway = metadata.linesHeadway[lineId] ?: return
        val headwayTolerance = metadata.headwayTolerance
        val scheduleDev = state.delay.takeIf { it >= -headwayTolerance * 60.0 } ?: headway

        assert(
            eventWriter.pushBusDelayData(
                BusDelayData(
                    vehicleId = vehicleId.toString(),
                    stopId = stopId,
                    linkId = linkId.toString(),
                    lineId = lineId.toString(),
                    timestamp = arrivalTime,
                    scheduleDev = scheduleDev,
                    boarding = state.boarding,
                    alighting = state.alighting
                )
            )
        )

        state.stopId = null
        state.arrivalTime = null
        state.boarding = 0
        state.alighting = 0
        state.delay = -1.0
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!startCollect.value) return

        if (event.personId.toString().startsWith("pt_")) return

        val state = vehicleStates[event.vehicleId] ?: return

        if (state.stopId != null) { state.boarding++ }
    }

    override fun handleEvent(event: PersonLeavesVehicleEvent) {
        if (!startCollect.value) return

        if (event.personId.toString().startsWith("pt_")) return

        val state = vehicleStates[event.vehicleId] ?: return

        if (state.stopId != null) { state.alighting++ }
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        vehicleStates.clear()
    }
}