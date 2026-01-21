package com.thomas.pt.extractor.online

import com.thomas.pt.extractor.metadata.MATSimMetadataStore
import com.thomas.pt.model.data.TripData
import com.thomas.pt.writer.core.MATSimEventWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.ActivityStartEvent
import org.matsim.api.core.v01.events.PersonDepartureEvent
import org.matsim.api.core.v01.events.PersonDepartureEvent.ATTRIBUTE_ROUTING_MODE
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler
import org.matsim.api.core.v01.population.Person

class TripHandler(
    private val targetIter: Int,
    private val writer: MATSimEventWriter
) :
    PersonDepartureEventHandler,
    PersonEntersVehicleEventHandler,
    ActivityStartEventHandler
{
    private val blacklist by lazy {
        MATSimMetadataStore.metadata.blacklist.map { it.toString() }.toSet()
    }
    private val _startCollect = MutableStateFlow(false)
    val startCollect = _startCollect.asStateFlow()
    private var tripMap = mutableMapOf<Id<Person>, TripData>()

    override fun handleEvent(event: PersonDepartureEvent) {
        if (!startCollect.value) return
        if (event.personId.toString().startsWith("pt")) return
        if (tripMap.containsKey(event.personId)) return

        tripMap[event.personId] = TripData(
            personId = event.personId.toString(),
            startTime = event.time,
            travelTime = 0.0,
            mainMode = event.attributes[ATTRIBUTE_ROUTING_MODE]?: return,
            vehList = mutableListOf()
        )
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!startCollect.value) return
        if (event.personId !in tripMap) return
        if (event.personId.toString().startsWith("pt")) return // Ignore bus driver trips

        val existingData = tripMap[event.personId]!!
        tripMap[event.personId] = existingData.copy(
            vehList = existingData.vehList + event.vehicleId.toString()
        )
    }

    override fun handleEvent(event: ActivityStartEvent) {
        if (!startCollect.value) return
        if (event.personId.toString().startsWith("pt")) return // Ignore bus driver trips
        if (event.actType == "pt interaction") return

        val trip = tripMap.remove(event.personId) ?: return

        if (
            trip.vehList.let {
                it.isEmpty() || it.all { veh -> veh in blacklist }
            }
        ) return

        require(
            writer.pushTripData(
                trip.copy(
                    travelTime = event.time - trip.startTime
                )
            )
        )
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        tripMap.clear()
    }
}
