package com.thomas.pt.extractor.offline

import com.thomas.pt.extractor.offline.model.OfflineEventHandler
import com.thomas.pt.model.data.BusTripData
import com.thomas.pt.writer.core.MATSimEventWriter

class BusTripExtractor(
    private val bus: Set<String>,
    private val linkLength: Map<String, Double>,
    private val writer: MATSimEventWriter
): OfflineEventHandler {

    companion object {
        const val TRANSIT_DRIVER_STARTS = "TransitDriverStarts"
        const val VEHICLE_ENTERS_TRAFFIC = "vehicle enters traffic"
        const val VEHICLE_LEAVES_TRAFFIC = "vehicle leaves traffic"
        const val LINK_ENTER = "entered link"
        const val LINK_LEAVE = "left link"
        const val PERSON_ENTER_VEHICLE = "PersonEntersVehicle"
        const val PERSON_LEAVE_VEHICLE = "PersonLeavesVehicle"
    }

    private data class BusState(
        val busId: String,
        val currentLinkId: String,
        val passengers: Int,
        val enterTime: Double,
        val pendingPassengers: Int
    )

    private val busTrips = mutableMapOf<String, BusState>()
    private val vehDriverMap = mutableMapOf<String, String>()

    override fun handleEvent(time: Double, type: String, attributes: Map<String, String>) {
        when (type) {
            TRANSIT_DRIVER_STARTS -> handleDriverStarts(attributes)
            VEHICLE_ENTERS_TRAFFIC -> handleVehicleEnters(time, attributes)
            LINK_ENTER -> handleLinkEnter(time, attributes)
            PERSON_ENTER_VEHICLE -> handlePersonEnters(attributes)
            PERSON_LEAVE_VEHICLE -> handlePersonLeaves(attributes)
            LINK_LEAVE -> handleLinkLeave(time, attributes)
            VEHICLE_LEAVES_TRAFFIC -> handleVehicleLeaves(time, attributes)
        }
    }

    private fun handleDriverStarts(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicleId"] ?: return
        if (vehicleId !in bus) return

        vehDriverMap[vehicleId] = attrs["driverId"] ?: return
    }

    private fun handleVehicleEnters(time: Double, attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        if (vehicleId !in bus) return

        busTrips[vehicleId] = BusState(
            busId = vehicleId,
            currentLinkId = attrs["link"] ?: return,
            passengers = 0,
            pendingPassengers = 0,
            enterTime = time
        )
    }

    private fun handleLinkEnter(time: Double, attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val trip = busTrips[vehicleId] ?: return

        busTrips[vehicleId] = trip.copy(
            currentLinkId = attrs["link"] ?: return,
            passengers = trip.pendingPassengers,
            enterTime = time
        )
    }

    private fun handlePersonEnters(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val personId = attrs["person"] ?: return

        if (vehicleId !in busTrips) return
        if (personId == vehDriverMap[vehicleId]) return
        val trip = busTrips[vehicleId] ?: return

        busTrips[vehicleId] = trip.copy(
            pendingPassengers = trip.pendingPassengers + 1
        )
    }

    private fun handlePersonLeaves(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val personId = attrs["person"] ?: return

        if (vehicleId !in busTrips) return
        if (personId == vehDriverMap[vehicleId]) return
        val trip = busTrips[vehicleId] ?: return

        busTrips[vehicleId] = trip.copy(
            pendingPassengers = trip.pendingPassengers - 1
        )
    }

    private fun handleLinkLeave(time: Double, attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val trip = busTrips[vehicleId] ?: return

        require(
            writer.pushBusTripData(
                BusTripData(
                    busId = trip.busId,
                    linkId = trip.currentLinkId,
                    linkLen = linkLength[trip.currentLinkId] ?: return,
                    havePassenger = trip.passengers > 0,
                    travelTime = time - trip.enterTime
                )
            )
        )
    }

    private fun handleVehicleLeaves(time: Double, attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val trip = busTrips.remove(vehicleId) ?: return
        require(trip.pendingPassengers == 0) {
            "Bus ${trip.busId} has ${trip.pendingPassengers} passengers upon leaving traffic"
        }

        require(
            writer.pushBusTripData(
                BusTripData(
                    busId = trip.busId,
                    linkId = trip.currentLinkId,
                    linkLen = linkLength[trip.currentLinkId] ?: return,
                    havePassenger = trip.passengers > 0,
                    travelTime = time - trip.enterTime
                )
            )
        )
        vehDriverMap.remove(vehicleId)
    }
}