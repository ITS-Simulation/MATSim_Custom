package com.thomas.pt.data.metadata

import com.thomas.pt.utility.Utility
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.network.Network
import org.matsim.pt.transitSchedule.api.TransitLine
import org.matsim.pt.transitSchedule.api.TransitSchedule
import org.matsim.pt.transitSchedule.api.TransitStopFacility
import org.matsim.vehicles.Vehicles

object MATSimMetadataStore {
    private lateinit var _metadata: MATSimMetadata
    val metadata: MATSimMetadata
        get() = _metadata.takeIf { ::_metadata.isInitialized }
            ?: throw IllegalStateException("Metadata not initialized. Call build() first.")

    private fun extractLinkMetadata(
        net: Network,
        schedule: TransitSchedule
    ): Map<Id<Link>, LinkMetadata> {
        val linkData = mutableMapOf<Id<Link>, LinkMetadata>()
        val linkLen = net.links.mapValues { (_, link) -> link.length }

        schedule.transitLines.values.forEach{ line ->
            line.routes.values.forEach { route ->
                val routeLinks: List<Id<Link>> = listOf(route.route.startLinkId) + 
                    route.route.linkIds + 
                    listOf(route.route.endLinkId)
                val departures: List<Double> = route.departures.values.map { it.departureTime }.sorted()

                if (departures.isNotEmpty()) {
                    val opHours = ((departures.last() - departures.first()) / 3600.0).coerceAtLeast(1.0)
                    val freq = departures.size / opHours

                    routeLinks.forEach { link ->
                        linkData.merge(link, LinkMetadata(
                            length = linkLen[link]!!,
                            busFreq = freq
                        )
                        ) { old, new ->
                            old.copy(busFreq = old.busFreq + new.busFreq)
                        }
                    }
                }
            }
        }

        net.links.forEach { (id, link) ->
            linkData.merge(id, LinkMetadata(link.length)) { old, _ -> old }
        }

        return linkData
    }

    fun build(
        yamlConfig: Map<String, Any>,
        net: Network,
        schedule: TransitSchedule,
        transitVehicles: Vehicles
    ) {
        assert(!::_metadata.isInitialized) { "Metadata already initialized." }

        // Calculate bus planned capacity
        val busConfig = Utility.getYamlSubconfig(yamlConfig, "bus")
        val seating: Int = busConfig["seating"] as Int
        val standing: Int = busConfig["standing"] as Int
        val headroom: Double = busConfig["cap_headroom"] as Double
        val busCap = (seating + standing * headroom).toInt()

        // Extract headway tolerance
        val headwayTolerance: Int = Utility.getYamlSubconfig(
            yamlConfig, "scoring", "wait_ride"
        ).let {
            it["headway_tolerance"] as Int
        }

        // Extract bus modes prefixes from config
        val matsimConfig = Utility.getYamlSubconfig(yamlConfig, "matsim")
        val modes: List<String> = (
            matsimConfig["bus_transport_modes"] as List<*>
        ).map { it as String }
        val busPrefix: String = matsimConfig["bus_type_prefix"] as String

        // Extract vehicle type mapping (bus/non-bus)
        val (bus, blacklist) = transitVehicles.vehicles.values
            .partition { vehicle -> "${vehicle.type.id}".startsWith(busPrefix) }

        // Extract lines headway
        val linesHeadways = schedule.transitLines
            .filter { (_, line) ->
                line.routes.values.any { route ->
                    modes.any { mode -> "${route.transportMode}" == mode }
                }
            }
            .mapValues { (_, line) ->
                val departures = line.routes.values.asSequence()
                    .flatMap { it.departures.values }
                    .map { it.departureTime }
                    .sorted()
                    .toSet()

                val duration = (departures.last() - departures.first()) / (departures.size - 1)
                duration.takeIf { departures.size > 1 }
            }

        // Extract link metadata
        val linkData = extractLinkMetadata(net, schedule)

        // Extract bus route information
        val busRoutes = mutableMapOf<Id<TransitLine>, MutableList<BusRouteMetadata>>()
        schedule.transitLines
            .filter { (_, line) ->
                line.routes.values.any { route ->
                    modes.any { mode -> "${route.transportMode}" == mode }
                }
            }.forEach { (id, line) ->
            line.routes.forEach { (_, route) ->
                val fullRoute: List<Id<Link>> = route.route.linkIds.toMutableList().apply {
                    addFirst(route.route.startLinkId)
                    add(route.route.endLinkId)
                }
                val routeStops: Set<Id<TransitStopFacility>>
                    = route.stops.map { it.stopFacility.id }.toSet()
                busRoutes.getOrPut(id) { mutableListOf() }.add(
                    BusRouteMetadata(route = fullRoute, stops = routeStops)
                )
            }
        }

        _metadata = MATSimMetadata(
            linkData = linkData,
            linesHeadway = linesHeadways,
            busRoutes = busRoutes,
            bus = bus.map { it.id }.toSet(),
            blacklist = blacklist.map { it.id }.toSet(),
            busCap = busCap,
            headwayTolerance = headwayTolerance,
        )
    }
}