package com.thomas.pt.event

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler
import org.matsim.vehicles.Vehicle
import com.thomas.pt.data.model.extractor.QTripData
import org.matsim.api.core.v01.events.ActivityStartEvent
import org.matsim.api.core.v01.events.PersonArrivalEvent
import org.matsim.api.core.v01.events.PersonDepartureEvent
import org.matsim.api.core.v01.events.PersonDepartureEvent.ATTRIBUTE_ROUTING_MODE
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.events.TransitDriverStartsEvent
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler
import org.matsim.api.core.v01.population.Person

/**

 * Output schema (link_records):
 * - vehId, startTime, travelTime, mainMode, vehIDList
 */
class QTripHandler(
    private val targetIter: Int
    ) :
    PersonDepartureEventHandler,
    PersonEntersVehicleEventHandler,
    ActivityStartEventHandler
{
    private var isCollecting = false
    private var tripMap = mutableMapOf<Id<Person>, QTripData>()

    override fun handleEvent(event: PersonDepartureEvent) {
        if (!isCollecting) return
        if(event.personId.toString().startsWith("pt")) return // Ignore bus driver trips
        if( tripMap.containsKey(event.personId)) return // departure đâu tiên của 1 trip mới khởi tạo record, nếu nó tồn tại r thì bỏ qua

        tripMap[event.personId] = QTripData(
            personId = event.personId.toString(),
            startTime = event.time,
            travelTime = 0.0,
            mainMode = event.attributes[ATTRIBUTE_ROUTING_MODE]?:"36",
            vehIDList = mutableListOf()
        )
    }

    override fun handleEvent(event: PersonEntersVehicleEvent) {
        if (!isCollecting) return
        if(event.personId.toString().startsWith("pt")) return // Ignore bus driver trips

        val existingData = tripMap[event.personId]
        if (existingData != null) {
            tripMap[event.personId] = existingData.copy(
                vehIDList = existingData.vehIDList + event.vehicleId.toString()
            )
        }

    }

    override fun handleEvent(event: ActivityStartEvent) {

        if (!isCollecting) return
        if(event.personId.toString().startsWith("pt")) return // Ignore bus driver trips
        if (event.actType == "pt interaction") return // Only end of trip
        if(!tripMap.containsKey(event.personId)) return // if no trip record, ignore

        // Nếu danh sách xe trống (đi bộ thuần túy), thì không ghi file
        if (tripMap[event.personId]?.vehIDList?.isEmpty() == true) {
            tripMap.remove(event.personId)
            return
        }

        java.io.File("QTesst/debug/debug_QTripHandler.csv").appendText(
            "${event.personId}," +
                    "${tripMap[event.personId]?.startTime }," +
                    "${(event.time - (tripMap[event.personId]?.startTime ?: event.time))}," +
                    "${tripMap[event.personId]?.mainMode }," +
                    "${tripMap[event.personId]?.vehIDList?.joinToString(separator = "|")}\n"
        )
        tripMap.remove(event.personId)
    }

    override fun reset(iteration: Int) {
        isCollecting = (iteration == targetIter)
        if (isCollecting) {
            java.io.File("QTesst/debug/debug_QTripHandler.csv").appendText("personId,startTime,travelTime,mainMode,vehIDList\n")
        }

        tripMap.clear()
        println("QTripHandler: Iteration $iteration started. Collecting? $isCollecting")
    }
}
