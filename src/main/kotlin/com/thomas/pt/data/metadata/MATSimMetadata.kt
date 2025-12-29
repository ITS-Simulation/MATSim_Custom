package com.thomas.pt.data.metadata

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.pt.transitSchedule.api.TransitLine
import org.matsim.vehicles.Vehicle

data class MATSimMetadata(
    val linkData: Map<Id<Link>, LinkMetadata>,
    val linesHeadway: Map<Id<TransitLine>, Double?>,
    val busRoutes: Map<Id<TransitLine>, List<BusRouteMetadata>>,

    val bus: Set<Id<Vehicle>>,
    val blacklist: Set<Id<Vehicle>>,

    val busCap: Int,
    val headwayTolerance: Int,
)