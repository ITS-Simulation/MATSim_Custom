package com.thomas.pt.data.metadata

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.pt.transitSchedule.api.TransitStopFacility

data class BusRouteMetadata(
    val route: List<Id<Link>>,
    val stops: Set<Id<TransitStopFacility>>,
)
