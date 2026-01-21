package com.thomas.pt.extractor.offline

import com.thomas.pt.extractor.offline.model.OfflineEventHandler
import com.thomas.pt.model.data.BusPassengerData
import com.thomas.pt.writer.core.MATSimEventWriter

class BusPassengerExtractor(
    private val bus: Set<String>,
    private val writer: MATSimEventWriter
) : OfflineEventHandler {
    
    companion object {
        const val TRANSIT_DRIVER_STARTS = "TransitDriverStarts"
        const val PERSON_ENTERS_VEHICLE = "PersonEntersVehicle"
        const val VEHICLE_LEAVES_TRAFFIC = "vehicle leaves traffic"
    }
    
    private val vehDriverMap = mutableMapOf<String, String>()
    
    override fun handleEvent(time: Double, type: String, attributes: Map<String, String>) {
        when (type) {
            TRANSIT_DRIVER_STARTS -> handleDriverStarts(attributes)
            PERSON_ENTERS_VEHICLE -> handlePersonEnters(attributes)
            VEHICLE_LEAVES_TRAFFIC -> handleVehicleLeaves(attributes)
        }
    }
    
    private fun handleDriverStarts(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicleId"] ?: return
        if (vehicleId !in bus) return
        
        vehDriverMap[vehicleId] = attrs["driverId"] ?: return
    }
    
    private fun handlePersonEnters(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val personId = attrs["person"] ?: return
        
        if (vehicleId !in vehDriverMap) return
        if (personId == vehDriverMap[vehicleId]) return
        
        assert(
            writer.pushBusPassengerData(
                BusPassengerData(
                    personId = personId,
                    busId = vehicleId
                )
            )
        )
    }
    
    private fun handleVehicleLeaves(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        vehDriverMap.remove(vehicleId)
    }
}
