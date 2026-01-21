package com.thomas.pt.extractor.offline

import com.thomas.pt.extractor.offline.model.OfflineEventHandler
import com.thomas.pt.model.data.TripData
import com.thomas.pt.writer.core.MATSimEventWriter

class TripExtractor(
    private val blacklist: Set<String>,
    private val writer: MATSimEventWriter
) : OfflineEventHandler {
    
    companion object {
        const val DEPARTURE = "departure"
        const val PERSON_ENTERS_VEHICLE = "PersonEntersVehicle"
        const val ACT_START = "actstart"
        const val ROUTING_MODE_ATTR = "computationalRoutingMode"
    }
    
    private val tripMap = mutableMapOf<String, TripData>()
    
    override fun handleEvent(time: Double, type: String, attributes: Map<String, String>) {
        when (type) {
            DEPARTURE -> handleDeparture(time, attributes)
            PERSON_ENTERS_VEHICLE -> handlePersonEnters(attributes)
            ACT_START -> handleActStart(time, attributes)
        }
    }
    
    private fun handleDeparture(time: Double, attrs: Map<String, String>) {
        val personId = attrs["person"] ?: return
        if (personId.startsWith("pt")) return
        if (personId in tripMap) return
        
        val mainMode = attrs[ROUTING_MODE_ATTR] ?: return
        
        tripMap[personId] = TripData(
            personId = personId,
            startTime = time,
            travelTime = 0.0,
            mainMode = mainMode,
            vehList = mutableListOf()
        )
    }
    
    private fun handlePersonEnters(attrs: Map<String, String>) {
        val personId = attrs["person"] ?: return
        val vehicleId = attrs["vehicle"] ?: return
        
        if (personId.startsWith("pt")) return
        val existing = tripMap[personId] ?: return
        
        tripMap[personId] = existing.copy(
            vehList = existing.vehList + vehicleId
        )
    }
    
    private fun handleActStart(time: Double, attrs: Map<String, String>) {
        val personId = attrs["person"] ?: return
        val actType = attrs["actType"] ?: return
        
        if (personId.startsWith("pt")) return
        if (actType == "pt interaction") return
        
        val trip = tripMap.remove(personId) ?: return

        if (
            trip.vehList.let {
                it.isEmpty() || it.all { veh -> veh in blacklist }
            }
        ) return
        
        require(
            writer.pushTripData(
            trip.copy(
                    travelTime = time - trip.startTime
                )
            )
        )
    }
}
