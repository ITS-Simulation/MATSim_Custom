package com.thomas.pt.data.scoring

import com.thomas.pt.utility.Utility
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.add
import org.jetbrains.kotlinx.dataframe.api.map
import org.jetbrains.kotlinx.dataframe.api.select
import org.jetbrains.kotlinx.dataframe.api.sum
import org.jetbrains.kotlinx.dataframe.io.readArrowIPC
import org.jetbrains.kotlinx.dataframe.io.writeArrowIPC
import java.io.DataInputStream
import java.io.File
import java.nio.file.Path
import kotlin.math.E
import kotlin.math.ln
import kotlin.math.pow
import kotlin.system.exitProcess

import org.slf4j.LoggerFactory
import java.io.DataOutputStream

// TODO: Finalize LOS calculation
class LOSCalculator(configPath: Path) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val config = Utility.loadYaml(configPath)

    // Files
    private val losRecordsFile = File(
        Utility.getYamlSubconfig(
            config,
            "files", "data"
        )["los_records"] as String)
    private val avgTripLengthFile = File(
        Utility.getYamlSubconfig(
            config,
            "files", "data"
        )["avg_trip_len"] as String)
    private val losScoresFile = File(
        Utility.getYamlSubconfig(
            config,
            "files", "data"
        )["los_scores"] as String)
    
    // Scoring Config
    private val scoringConfig = Utility.getYamlSubconfig(config, "scoring")
    private val waitRideConfig = Utility.getYamlSubconfig(scoringConfig, "wait_ride")
    private val amenityConfig = Utility.getYamlSubconfig(scoringConfig, "amenity")
    private val pedEnvConfig = Utility.getYamlSubconfig(scoringConfig, "ped_env")

    // Transit Wait-Ride Params
    private val elasticity = (waitRideConfig["elas"] as Number).toDouble()
    private val baseTravelTime = (waitRideConfig["base_travel_time"] as Number).toDouble()

    // Amenity Params
    private val shelterTime = (amenityConfig["shelter"] as Number).toDouble()
    private val shelterRate = (amenityConfig["shelter_rate"] as Number).toDouble()
    private val benchTime = (amenityConfig["bench"] as Number).toDouble()
    private val benchRate = (amenityConfig["bench_rate"] as Number).toDouble()

    // Pedestrian Environment Params
    private val outsideLaneWidth = (pedEnvConfig["outside_lane_width"] as Number).toDouble()
    private val bikeLaneWidth = (pedEnvConfig["bike_lane_width"] as Number).toDouble()
    private val parkingLaneWidth = (pedEnvConfig["parking_lane_width"] as Number).toDouble()
    private val volumeThreshold = (pedEnvConfig["volume_threshold"] as Number).toDouble()
    private val streetParking = (pedEnvConfig["street_parking"] as Number).toDouble()
    private val sidewalkBuffer = (pedEnvConfig["sidewalk_buffer"] as Number).toDouble()
    private val bufferCoeff = (pedEnvConfig["buffer_coeff"] as Number).toDouble()
    private val sidewalkWidth = (pedEnvConfig["sidewalk_width"] as Number).toDouble()

    /**
     * Headway Factor: f_h = 4 * e^(-1.434 / (freq + 0.001))
     * Higher frequency -> lower factor -> better service
     */
    private fun calculateHeadwayFactor(busFrequency: Double): Double {
        val powFactor = -1.434 / (busFrequency + 0.001)
        return 4 * E.pow(powFactor)
    }

    /**
     * Load Factor Weight: Penalizes crowded vehicles
     * - lf <= 0.8: weight = 1.0
     * - lf <= 1.0: weight = 1 + 4(lf - 0.8) / (4.2 * lf)
     * - lf > 1.0: additional penalty for standees
     */
    private fun calculateLoadFactorWeight(loadFactor: Double): Double {
        if (loadFactor <= 0.8) return 1.0
        val baseFactor = 1 + 4 * (loadFactor - 0.8) / (4.2 * loadFactor)
        if (loadFactor <= 1.0) return baseFactor
        return baseFactor + (loadFactor - 1.0) * (6.5 + 5 * (loadFactor - 1.0)) / (4.2 * loadFactor)
    }

    /**
     * Travel Time Factor: Based on perceived travel time vs base travel time
     * f_tt = ((elas - 1) * base_tt - (elas + 1) * perc_tt) / ((elas - 1) * perc_tt - (elas + 1) * base_tt)
     */
    private fun calculateTravelTimeFactor(perceivedTravelTime: Double): Double {
        val nom = (elasticity - 1) * baseTravelTime - (elasticity + 1) * perceivedTravelTime
        val denom = (elasticity - 1) * perceivedTravelTime - (elasticity + 1) * baseTravelTime
        require(denom != 0.0) { "Denominator in travel time factor calculation is zero." }
        return nom / denom
    }

    /**
     * Amenity Time: Time saved due to shelter/bench availability
     * T_at = (shelter * shelter_rate + bench * bench_rate) / avg_trip_length
     */
    private fun calculateAmenityTime(avgTripLengthKm: Double): Double 
        = (shelterTime * shelterRate + benchTime * benchRate) / avgTripLengthKm

    /**
     * Wait-Ride Score: S_w-r = f_h * f_tt
     *
     * Perceived Travel Time (T_ptt):
     * T_ptt = f_pl * (60 / S) + 2 * T_ex - T_at
     * where:
     * - f_pl: Passenger Weighting Load Factor
     * - S: Average Bus Speed (km/h)
     * - T_ex: Average Excess Wait Time (min/km)
     * - T_at: Perceived Amenity Time (min/km)
     */
    private fun calculateWaitRideScore(
        busFrequency: Double,
        loadFactor: Double,
        busSpeedMps: Double,
        ewtSeconds: Double,
        avgTripLengthM: Double
    ): Double {
        val fh = calculateHeadwayFactor(busFrequency)
        val fpl = calculateLoadFactorWeight(loadFactor)
        val busSpeedKmh = busSpeedMps * 3.6  // m/s -> km/h
        val ewt = (ewtSeconds / 60) / (avgTripLengthM / 1000)  // seconds -> min/km
        val amenity = calculateAmenityTime(avgTripLengthM / 1000)

        // Perceived Travel Time = f_pl * (60 / S) + 2 * T_ex - T_at
        val perceivedTravelTime = fpl * (60 / busSpeedKmh) + 2 * ewt - amenity
        val ftt = calculateTravelTimeFactor(perceivedTravelTime)

        return fh * ftt
    }

    /**
     * Pedestrian Environment Score: I_p = 6.0468 + f_w + f_v + f_s
     *
     * f_v: Traffic volume factor = 0.0091 * veh_flow / 4
     * f_s: Traffic speed factor = 4 * (speed_mph / 100)^2
     * f_w: Cross-section factor (based on lane widths, parking, sidewalk)
     */
    private fun calculatePedestrianScore(vehFlow: Double, avgSpeedMps: Double): Double {
        // f_v: Traffic volume adjustment
        val fv = 0.0091 * vehFlow / 4

        // f_s: Traffic speed adjustment (convert m/s -> mph)
        val avgSpeedMph = avgSpeedMps * 3.6 / 1.6
        val fs = 4 * (avgSpeedMph / 100).pow(2)

        // f_w: Cross-section adjustment
        val adjParkingWidth = maxOf(0.0, parkingLaneWidth - 1.5)
        val w1 = if (streetParking >= 0.25) 10.0 else bikeLaneWidth + adjParkingWidth
        val wt = if (streetParking == 0.0) outsideLaneWidth + bikeLaneWidth + adjParkingWidth
                 else outsideLaneWidth + bikeLaneWidth
        val wv = if (vehFlow > volumeThreshold) wt else wt * (2 - 0.005 * vehFlow)

        val sidewalkBufferIdx = sidewalkBuffer * bufferCoeff
        val waa = minOf(sidewalkWidth, 10.0)
        val sidewalkWidthIdx = waa * (6.0 - 0.3 * waa)

        val fw = -1.2276 * ln(wv + 0.5 * w1 + 50 * streetParking + sidewalkBufferIdx + sidewalkWidthIdx)

        return 6.0468 + fw + fv + fs
    }

    /**
     * Final LOS Score: LOS = 6.0 - 1.5 * wait_ride_score + 0.15 * ped_score
     */
    private fun calculateLOS(waitRideScore: Double, pedScore: Double): Double = 6.0 - 1.5 * waitRideScore + 0.15 * pedScore

    /**
     * LOS Grade: A-F based on score thresholds
     */
    private fun calculateLOSGrade(los: Double): String = when {
        los <= 2.0 -> "A"
        los <= 2.75 -> "B"
        los <= 3.5 -> "C"
        los <= 4.25 -> "D"
        los <= 5.0 -> "E"
        else -> "F"
    }

    /**
     * Process a DataFrame with LOS data columns and add scores.
     * Expected columns: ewt, veh_flow, avg_speed, bus_link_avg_speed, avg_lf, bus_frequency
     */
    @Suppress("RemoveExplicitTypeArguments")
    private fun processDataFrame(df: AnyFrame, avgTripLengthM: Double): AnyFrame
        = df
            .add("wait_ride_score") {
                val busFreq = "bus_frequency"<Double>()
                val lf = "avg_lf"<Double>()
                val busSpeed = "bus_link_avg_speed"<Double>()
                val ewt = "ewt"<Double>()
                calculateWaitRideScore(busFreq, lf, busSpeed, ewt, avgTripLengthM)
            }
            .add("ped_score") {
                val vehFlow = "veh_flow"<Double>()
                val avgSpeed = "avg_speed"<Double>()
                calculatePedestrianScore(vehFlow, avgSpeed)
            }
            .add("los") { calculateLOS("wait_ride_score"<Double>(), "ped_score"<Double>()) }
            .add("los_grade") { calculateLOSGrade("los"<Double>()) }
            .select("link_id", "wait_ride_score", "ped_score", "los", "los_grade", "pax_seconds", "total_duration", "length", "bus_capacity")

    private fun aggregateLOS(df: AnyFrame, mode: AggregationMode): Double {
        val weightedDf = when (mode) {
            AggregationMode.PASSENGER_TRIP -> {
                df.add("avg_load") { "pax_seconds"<Double>() / "total_duration"<Double>() }
                    .add("weight") { "length"<Double>() * "avg_load"<Double>() }
            }
            AggregationMode.PASSENGER_TIME -> {
                df.add("weight") { "pax_seconds"<Double>() }
            }
            AggregationMode.OPERATOR_VEH_TIME -> {
                df.add("weight") { "total_duration"<Double>() }
            }
            AggregationMode.OPERATOR_LOAD -> {
                df.add("weight") {
                    "length"<Double>() * "total_duration"<Double>() * "bus_capacity"<Int>()
                }
            }
        }

        val totalWeight = weightedDf.sum { "weight"<Double>() }
        if (totalWeight == 0.0) return 0.0
        return weightedDf.map { "los"<Double>() * "weight"<Double>() }.sum() / totalWeight
    }


    fun calculateLOS(aggMode: AggregationMode, out: File) {
        logger.info("Starting LOS Calculation with aggregation mode: $aggMode")
        try {
            val avgTripLength = DataInputStream(
                avgTripLengthFile.inputStream()
            ).use { it.readDouble() }
            
            val df = processDataFrame(
                DataFrame.readArrowIPC(losRecordsFile),
                avgTripLength
            )
            
            df.writeArrowIPC(losScoresFile)
            
            // Aggregation
            val aggScore = aggregateLOS(df, aggMode)
            logger.info("System-wide LOS Score ($aggMode): %.2f".format(aggScore))
            DataOutputStream(out.outputStream()).use { it.writeDouble(aggScore) }
        } catch (e: Exception) {
            logger.error("Failed to calculate LOS for mode $aggMode", e)
            exitProcess(1)
        }
    }
}