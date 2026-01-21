package com.thomas.pt.extractor.online

import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.model.data.BusTripData
import com.thomas.pt.writer.core.MATSimEventWriter
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
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

class BusTripHandler(
    private val targetIter: Int,
    private val writer: MATSimEventWriter,
) :
    TransitDriverStartsEventHandler,
    VehicleEntersTrafficEventHandler,
    VehicleLeavesTrafficEventHandler,
    LinkEnterEventHandler,
    LinkLeaveEventHandler,
    PersonEntersVehicleEventHandler,
    PersonLeavesVehicleEventHandler
{
    private val metadata by lazy { MATSimMetadataStore.metadata }

    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()

    private data class BusState(
        val busId: Id<Vehicle>,
        val currentLinkId: Id<Link>,
        val passengers: Int,
        val pendingPassengers: Int
    )

    private val busTrips = mutableMapOf<Id<Vehicle>, BusState>()
    private var vehDriverMap = mutableMapOf<Id<Vehicle>, Id<Person>>()

    override fun handleEvent(event: TransitDriverStartsEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in metadata.bus) return

        vehDriverMap[event.vehicleId] = event.driverId
    }

    override fun handleEvent(event: VehicleEntersTrafficEvent) {
        if (!startCollect.value) return
        if (event.vehicleId !in metadata.bus) return

        busTrips[event.vehicleId] = BusState(
            busId = event.vehicleId,
            currentLinkId = event.linkId,
            passengers = 0,
            pendingPassengers = 0
        )
    }

    override fun handleEvent(event: LinkEnterEvent) {
        if (!startCollect.value) return
        val trip = busTrips[event.vehicleId] ?: return

        busTrips[event.vehicleId] = trip.copy(
            currentLinkId = event.linkId,
            passengers = trip.pendingPassengers
        )
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!startCollect.value) return
        if (event.personId == vehDriverMap[event.vehicleId]) return
        val trip = busTrips[event.vehicleId] ?: return

        busTrips[event.vehicleId] = trip.copy(
            pendingPassengers = trip.pendingPassengers + 1
        )
    }

    override fun handleEvent(event: PersonLeavesVehicleEvent) {
        if (!startCollect.value) return
        if (event.personId == vehDriverMap[event.vehicleId]) return
        val trip = busTrips[event.vehicleId] ?: return

        busTrips[event.vehicleId] = trip.copy(
            pendingPassengers = trip.pendingPassengers - 1
        )
    }

    override fun handleEvent(event: LinkLeaveEvent) {
        if (!startCollect.value) return
        val trip = busTrips[event.vehicleId] ?: return

        require(
            writer.pushBusTripData(
                BusTripData(
                    busId = trip.busId.toString(),
                    linkId = trip.currentLinkId.toString(),
                    linkLen = metadata.linkLength[trip.currentLinkId] ?: return,
                    havePassenger = trip.passengers > 0,
                )
            )
        )
    }

    override fun handleEvent(event: VehicleLeavesTrafficEvent) {
        if (!startCollect.value) return
        val trip = busTrips.remove(event.vehicleId) ?: return
        require(trip.pendingPassengers == 0) {
            "Bus ${trip.busId} has ${trip.pendingPassengers} passengers upon leaving traffic"
        }

        require(
            writer.pushBusTripData(
                BusTripData(
                    busId = trip.busId.toString(),
                    linkId = trip.currentLinkId.toString(),
                    linkLen = metadata.linkLength[trip.currentLinkId] ?: return,
                    havePassenger = trip.passengers > 0,
                )
            )
        )
        vehDriverMap.remove(event.vehicleId)
    }

    override fun reset(iterations: Int) {
        _startCollect.update { iterations == targetIter }
        busTrips.clear()
        vehDriverMap.clear()
    }
}