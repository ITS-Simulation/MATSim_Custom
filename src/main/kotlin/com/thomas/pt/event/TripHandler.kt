package com.thomas.pt.event

import com.thomas.pt.data.metadata.MATSimMetadataStore
import com.thomas.pt.data.model.extractor.TripData
import com.thomas.pt.data.writer.MATSimEventWriter
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
    private val eventWriter: MATSimEventWriter
) :
    PersonDepartureEventHandler,
    PersonEntersVehicleEventHandler,
    ActivityStartEventHandler
{
    private val metadata by lazy { MATSimMetadataStore.metadata }
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

        if (event.vehicleId in metadata.blacklist) {
            tripMap.remove(event.personId)
            return
        }

        val existingData = tripMap[event.personId]!!
        tripMap[event.personId] = existingData.copy(
            vehList = existingData.vehList + event.vehicleId.toString()
        )
    }

    override fun handleEvent(event: ActivityStartEvent) {
        if (!startCollect.value) return
        if (event.personId !in tripMap) return
        if (event.personId.toString().startsWith("pt")) return // Ignore bus driver trips
        if (event.actType == "pt interaction") return

        if (tripMap[event.personId]!!.vehList.isEmpty()) {
            tripMap.remove(event.personId)
            return
        }

        assert(
            eventWriter.pushTripData(
                tripMap[event.personId]!!.copy(
                    travelTime = event.time - tripMap[event.personId]!!.startTime
                )
            )
        )

        tripMap.remove(event.personId)
    }

    override fun reset(iteration: Int) {
        _startCollect.update { iteration == targetIter }
        tripMap.clear()
    }
}
