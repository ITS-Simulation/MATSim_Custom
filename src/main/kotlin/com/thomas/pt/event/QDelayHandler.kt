package com.thomas.pt.event

import com.thomas.pt.data.model.extractor.QDelayData
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler

/**
 * Output schema (link_records):
 * - facilityId, arrTime, depTime, arrDelay, depDelay
 */
class QDelayHandler(
    private val targetIter: Int
    ) :
    VehicleArrivesAtFacilityEventHandler,
    VehicleDepartsAtFacilityEventHandler
    {
        private var isCollecting = false
        private val busMap = mutableMapOf<Id<Vehicle>, QDelayData>()

        override fun handleEvent(event: VehicleArrivesAtFacilityEvent) {
            if (!isCollecting) return
            busMap[event.vehicleId] = QDelayData(
                arrTime = event.time,
                depTime = 0.0,
                facilityId = event.facilityId.toString(),
                arrDelay = event.delay,
                depDelay = 0.0
            )
        }

        override fun handleEvent(event: VehicleDepartsAtFacilityEvent) {
            if (!isCollecting) return

            val exitData = busMap[event.vehicleId]
            if(exitData != null) {
                busMap[event.vehicleId] = exitData.copy(
                    depTime = event.time,
                    depDelay = event.delay
                )
            }

            java.io.File("QTesst/debug/debug_QDelayHandler.csv").appendText(
                "${busMap[event.vehicleId]?.facilityId}," +
                "${busMap[event.vehicleId]?.arrTime}," +
                "${busMap[event.vehicleId]?.depTime}," +
                        "${busMap[event.vehicleId]?.arrDelay}," +
                        "${busMap[event.vehicleId]?.depDelay}\n")
            busMap.remove(event.vehicleId)
        }

        override fun reset(iteration: Int) {
            isCollecting = (iteration == targetIter)
            if(isCollecting) {
                java.io.File("QTesst/debug/debug_QDelayHandler.csv").appendText("facilityId,arrTime,depTime,arrDelay,depDelay\n")
            }
            busMap.clear()

            println("QDelayHandler: Iteration $iteration started. Collecting? $isCollecting")
        }
    }