package com.thomas.pt.data.metadata

import com.thomas.pt.utility.Utility
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Population
import org.matsim.pt.transitSchedule.api.TransitSchedule
import org.matsim.pt.transitSchedule.api.TransitStopFacility
import org.matsim.vehicles.Vehicles
import kotlin.collections.get

object MATSimMetadataStore {
    private lateinit var _metadata: MATSimMetadata
    val metadata: MATSimMetadata
        get() = _metadata.takeIf { ::_metadata.isInitialized }
            ?: throw IllegalStateException("Metadata not initialized. Call build() first.")


    private fun extractCoverage(
        netBound: NetworkBoundaries,
        radius: Double,
        plan: Population,
        schedule: TransitSchedule,
        modeFilter: List<String>
    ): Double {
        // Get total population and population home
        val totalPop = plan.persons.size.toDouble()
        var popCoveredWithBus = 0
        val popHome: List<Coord> = plan.persons.values.map { person ->
            person.selectedPlan
                .planElements
                .filterIsInstance<Activity>()
                .first { it.type == "home" }
                .coord
        }

        val busStops = schedule.transitLines
            .filter { (_, line) ->
                line.routes.values.any { route ->
                    modeFilter.any { mode -> "${route.transportMode}" == mode }
                }
            }
            .values.flatMap { line ->
                line.routes.values.flatMap { route ->
                    route.stops.map { it.stopFacility }
                }
            }.toSet()

        val qt = netBound.genQuadTree<TransitStopFacility>()
        busStops.forEach { stop ->
            qt.put(stop.coord.x, stop.coord.y, stop)
        }
        popHome.forEach { home ->
            qt.getDisk(home.x, home.y, radius)
                .isNotEmpty().let { if (it) popCoveredWithBus++ }
        }

        return popCoveredWithBus / totalPop
    }

    private fun calculateServiceHours(schedule: TransitSchedule): Double {
        return schedule.transitLines.values
            .flatMap { it.routes.values }
            .sumOf { route ->
                val arrival = route.stops.last().arrivalOffset
                val departure = route.stops.first().departureOffset
                if (arrival.isDefined && departure.isDefined) {
                    (arrival.seconds() - departure.seconds()) * route.departures.size
                } else {
                    0.0
                }
            } / 3600.0
    }

    fun build(
        yamlConfig: Map<String, Any>,
        netBound: NetworkBoundaries,
        plan: Population,
        schedule: TransitSchedule,
        transitVehicles: Vehicles
    ) {
        assert(!::_metadata.isInitialized) { "Metadata is already initialized" }

        // Extract headway tolerance and coverage radius
        val (radius, earlyHeadwayTolerance, lateHeadwayTolerance, travelTimeBaseline)
            = Utility.getYamlSubconfig(yamlConfig, "scoring", "params").let {
                arrayOf(
                    it["coverage_radius"] as Double,
                    it["early_headway_tolerance"] as Double,
                    it["late_headway_tolerance"] as Double,
                    it["travel_time_baseline"] as Double,
                )
            }

        // Extract bus modes prefixes from config
        val matsimConfig = Utility.getYamlSubconfig(yamlConfig, "matsim")
        val modeFilter: List<String> = (matsimConfig["bus_transport_modes"] as List<*>)
            .map { it as String }
        val busPrefix: String = matsimConfig["bus_type_prefix"] as String

        // Extract vehicle type mapping (bus/non-bus)
        val (bus, blacklist) = transitVehicles.vehicles.values
            .partition { vehicle -> "${vehicle.type.id}".startsWith(busPrefix) }

        _metadata = MATSimMetadata(
            totalPopulation = plan.persons.size,
            serviceCoverage = extractCoverage(netBound, radius, plan, schedule, modeFilter),
            bus = bus.map { it.id }.toSet(),
            blacklist = blacklist.map { it.id }.toSet(),
            earlyHeadwayTolerance = earlyHeadwayTolerance,
            lateHeadwayTolerance = lateHeadwayTolerance,
            travelTimeBaseline = travelTimeBaseline,
            totalServiceHours = calculateServiceHours(schedule)
        )
    }
}