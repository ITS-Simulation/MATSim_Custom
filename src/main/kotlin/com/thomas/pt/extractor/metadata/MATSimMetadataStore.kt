package com.thomas.pt.extractor.metadata

import com.thomas.pt.model.metadata.MATSimMetadata
import com.thomas.pt.model.metadata.NetworkBoundaries
import com.thomas.pt.utils.Utility
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Population
import org.matsim.pt.transitSchedule.api.TransitSchedule
import org.matsim.pt.transitSchedule.api.TransitStopFacility
import org.matsim.vehicles.Vehicles

object MATSimMetadataStore {
    private lateinit var _metadata: MATSimMetadata
    val metadata: MATSimMetadata
        get() = _metadata.takeIf { ::_metadata.isInitialized }
            ?: throw IllegalStateException("Metadata not initialized. Call build() first.")

    private fun getNetworkBoundary(
        net: Network,
        transitStops: Set<TransitStopFacility> = emptySet()
    ): NetworkBoundaries {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        fun update(x: Double, y: Double) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        net.nodes.values.forEach { update(it.coord.x, it.coord.y) }
        transitStops.forEach { update(it.coord.x, it.coord.y) }

        return NetworkBoundaries(
            minX = minX - 1.0,
            minY = minY - 1.0,
            maxX = maxX + 1.0,
            maxY = maxY + 1.0,
        )
    }

    private fun extractTransitRouteRatios(
        net: Network,
        schedule: TransitSchedule,
        modeFilter: List<String>
    ): Double {
        val linkMap = net.links
        val totalLinkLen = net.links.values.sumOf { it.length }
        val coveredLinkLen = schedule.transitLines.values.asSequence()
            .filter { line ->
                line.routes.values.any { route ->
                    modeFilter.any { mode -> "${route.transportMode}" == mode }
                }
            }
            .flatMap { line ->
                line.routes.values.asSequence().flatMap { it.route.linkIds.asSequence() }
            }
            .distinct()
            .sumOf { linkId -> linkMap[linkId]?.length ?: 0.0 }

        return coveredLinkLen / totalLinkLen
    }

    private fun extractServiceCoverage(
        net: Network,
        radius: Double,
        plan: Population,
        schedule: TransitSchedule,
        modeFilter: List<String>
    ): Double {
        var popCoveredWithBus = 0

        val busStops = schedule.transitLines.values.asSequence()
            .filter { line ->
                line.routes.values.any { route ->
                    modeFilter.any { mode -> "${route.transportMode}" == mode }
                }
            }
            .flatMap { line ->
                line.routes.values.asSequence().flatMap { route ->
                    route.stops.asSequence().map { it.stopFacility }
                }
            }.toSet()

        getNetworkBoundary(net, busStops)
            .genQuadTree<TransitStopFacility>()
            .apply {
                busStops.forEach { stop ->
                    put(stop.coord.x, stop.coord.y, stop)
                }
                plan.persons.values
                    .map { person ->
                        person.selectedPlan
                            .planElements
                            .filterIsInstance<Activity>()
                            .first { it.type == "home" }
                            .coord
                    }.forEach { home ->
                        getDisk(home.x, home.y, radius)
                            .isNotEmpty().let { if (it) popCoveredWithBus++ }
                    }
            }


        return popCoveredWithBus / plan.persons.size.toDouble()
    }

    fun build(
        yamlConfig: Map<String, Any>,
        net: Network,
        plan: Population,
        schedule: TransitSchedule,
        transitVehicles: Vehicles
    ) {
        require(!::_metadata.isInitialized) { "Metadata is already initialized" }

        // Extract headway tolerance and coverage radius
        val (radius, earlyHeadwayTolerance, lateHeadwayTolerance, travelTimeBaseline, productivityBaseline)
            = Utility.getYamlSubconfig(yamlConfig, "scoring", "params").let {
                arrayOf(
                    it["coverage_radius"] as Double,
                    it["early_headway_tolerance"] as Double,
                    it["late_headway_tolerance"] as Double,
                    it["travel_time_baseline"] as Double,
                    it["productivity_baseline"] as Double,
                )
            }

        // Extract bus modes prefixes from config
        val matsimConfig = Utility.getYamlSubconfig(yamlConfig, "matsim")
        val modeFilter: List<String> = (matsimConfig["bus_transport_modes"] as List<*>)
            .map { it as String }
        val busMarkers: List<String> = (matsimConfig["bus_type"] as List<*>).map { it as String }

        // Extract vehicle type mapping (bus/non-bus)
        val (bus, blacklist) = transitVehicles.vehicles.values
            .partition { vehicle ->
                busMarkers.any { mark -> "${vehicle.type.id}".contains(mark, true) }
            }

        _metadata = MATSimMetadata(
            totalPopulation = plan.persons.size,
            transitRouteRatios = extractTransitRouteRatios(net, schedule, modeFilter),
            serviceCoverage = extractServiceCoverage(net, radius, plan, schedule, modeFilter),
            bus = bus.map { it.id }.toSet(),
            blacklist = blacklist.map { it.id }.toSet(),
            linkLength = net.links.entries.associate { it.key.toString() to (it.value.length / 1000.0) },
            earlyHeadwayTolerance = earlyHeadwayTolerance,
            lateHeadwayTolerance = lateHeadwayTolerance,
            travelTimeBaseline = travelTimeBaseline,
            productivityBaseline = productivityBaseline
        )
    }
}