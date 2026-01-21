package com.thomas.pt.model.metadata

import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

data class MATSimMetadata(
    val serviceCoverage: Double,
    val totalPopulation: Int,
    val bus: Set<Id<Vehicle>>,
    val blacklist: Set<Id<Vehicle>>,
    val earlyHeadwayTolerance: Double,
    val lateHeadwayTolerance: Double,
    val travelTimeBaseline: Double,
    val totalServiceHours: Double,
)