package com.thomas.pt.extractor.offline

import com.thomas.pt.extractor.offline.model.OfflineEventHandler
import com.thomas.pt.model.data.BusDelayData
import com.thomas.pt.writer.core.MATSimEventWriter

class BusDelayExtractor(
    private val bus: Set<String>,
    private val writer: MATSimEventWriter
) : OfflineEventHandler {
    
    companion object {
        const val VEHICLE_ARRIVES = "VehicleArrivesAtFacility"
        const val VEHICLE_DEPARTS = "VehicleDepartsAtFacility"
    }
    
    private val busMap = mutableMapOf<String, BusDelayData>()
    
    override fun handleEvent(time: Double, type: String, attributes: Map<String, String>) {
        when (type) {
            VEHICLE_ARRIVES -> handleArrival(attributes)
            VEHICLE_DEPARTS -> handleDeparture(attributes)
        }
    }
    
    private fun handleArrival(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        if (vehicleId !in bus) return
        
        busMap[vehicleId] = BusDelayData(
            stopId = attrs["facility"] ?: return,
            arrDelay = attrs["delay"]?.toDoubleOrNull() ?: Double.NaN,
            depDelay = 0.0
        )
    }
    
    private fun handleDeparture(attrs: Map<String, String>) {
        val vehicleId = attrs["vehicle"] ?: return
        val existing = busMap.remove(vehicleId) ?: return
        
        require(
            writer.pushBusDelayData(
                existing.copy(
                    depDelay = attrs["delay"]?.toDoubleOrNull() ?: Double.NaN
                )
            )
        )

        busMap.remove(vehicleId)
    }
}
