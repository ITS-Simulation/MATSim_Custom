package com.thomas.pt.model.metadata

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.vehicles.Vehicle

data class MATSimMetadata(
    val transitRouteRatios: Double,
    val serviceCoverage: Double,
    val totalPopulation: Int,
    val linkLength: Map<Id<Link>, Double>,
    val bus: Set<Id<Vehicle>>,
    val blacklist: Set<Id<Vehicle>>,
    val earlyHeadwayTolerance: Double,
    val lateHeadwayTolerance: Double,
    val productivityBaseline: Double,
    val travelTimeBaseline: Double,
)