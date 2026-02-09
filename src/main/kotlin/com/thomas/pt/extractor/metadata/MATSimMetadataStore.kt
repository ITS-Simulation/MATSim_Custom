package com.thomas.pt.extractor.metadata

import com.thomas.pt.model.metadata.MATSimMetadata
import com.thomas.pt.model.metadata.NetworkBoundaries
import com.thomas.pt.utils.Utility
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
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

    private fun getNetworkBoundary(
        net: Network,
        transitStops: Set<TransitStopFacility> = emptySet()
    ): NetworkBoundaries {
        val nodeCoords = net.nodes.values.map { it.coord }
        val stopCoords = transitStops.map { it.coord }
        val allCoords = nodeCoords + stopCoords

        return NetworkBoundaries(
            minX = allCoords.minOf { it.x } - 1.0,
            minY = allCoords.minOf { it.y } - 1.0,
            maxX = allCoords.maxOf { it.x } + 1.0,
            maxY = allCoords.maxOf { it.y } + 1.0,
        )
    }

    private fun extractTransitRouteRatios(
        net: Network,
        schedule: TransitSchedule,
        modeFilter: List<String>
    ): Double {
        val linkMap = net.links
        val totalLinkLen = net.links.values.sumOf { it.length }
        val coveredLinkLen = schedule.transitLines
            .filter { (_, line) ->
                line.routes.values.any { route ->
                    modeFilter.any { mode -> "${route.transportMode}" == mode }
                }
            }
            .values.flatMap { line ->
                line.routes.values.flatMap { it.route.linkIds }
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
            linkLength = net.links.mapValues { (_, link) -> link.length / 1000.0 },
            earlyHeadwayTolerance = earlyHeadwayTolerance,
            lateHeadwayTolerance = lateHeadwayTolerance,
            travelTimeBaseline = travelTimeBaseline,
            productivityBaseline = productivityBaseline
        )
    }
}