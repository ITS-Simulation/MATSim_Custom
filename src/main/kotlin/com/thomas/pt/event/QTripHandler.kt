package com.thomas.pt.event

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler
import org.matsim.vehicles.Vehicle
import com.thomas.pt.data.model.extractor.QTripData
import org.matsim.api.core.v01.events.TransitDriverStartsEvent
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler

/**

 * Output schema (link_records):
 * - vehId, startTime, travelTime, mode
 */
class QTripHandler(
    private val targetIter: Int
    ) :
        VehicleEntersTrafficEventHandler,
        VehicleLeavesTrafficEventHandler,
        TransitDriverStartsEventHandler
    {
        private var isCollecting = false
        private var tripMap = mutableMapOf<Id<Vehicle>, QTripData>()

        override fun handleEvent(event: TransitDriverStartsEvent) {
            if (!isCollecting) return

            tripMap[event.vehicleId] = QTripData(
                startTime = 0.0,
                travelTime = 0.0,
                vehId = event.vehicleId.toString(),
                mode = "pt" // Mode can be filled in later if needed
            )
        }

        override fun handleEvent(event: VehicleEntersTrafficEvent) {
            if (!isCollecting) return

            val existingData = tripMap[event.vehicleId]
            if (existingData != null && existingData.mode == "pt") {
                tripMap[event.vehicleId] = existingData.copy(
                    startTime = event.time
                )
            }
            else{
                tripMap[event.vehicleId] = QTripData(
                    startTime = event.time,
                    travelTime = 0.0,
                    vehId = event.vehicleId.toString(),
                    mode = tripMap[event.vehicleId]?.mode ?: event.networkMode // Mode can be filled in later if needed
                )
            }

        }

        override fun handleEvent(event: VehicleLeavesTrafficEvent) {
            if (!isCollecting) return

            val existingData = tripMap[event.vehicleId]
            if (existingData != null)
            {
                tripMap[event.vehicleId] = existingData.copy(
                    travelTime = event.time - (existingData.startTime)
                )
            }

            java.io.File("QTesst/debug/debug_QTripHandler.csv").appendText(
                "${tripMap[event.vehicleId]?.vehId}," +
                "${tripMap[event.vehicleId]?.startTime}," +
                "${tripMap[event.vehicleId]?.travelTime}," +
                "${tripMap[event.vehicleId]?.mode}\n"
            )
            tripMap.remove(event.vehicleId)
        }

        override fun reset(iteration: Int) {
            isCollecting = (iteration == targetIter)
            if(isCollecting) {
                java.io.File("QTesst/debug/debug_QTripHandler.csv").appendText("vehId,startTime,travelTime,mode\n")
            }

            tripMap.clear()
            println("QTripHandler: Iteration $iteration started. Collecting? $isCollecting")
        }
}