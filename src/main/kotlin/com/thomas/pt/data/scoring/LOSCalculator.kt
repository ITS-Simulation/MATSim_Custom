package com.thomas.pt.data.scoring

import com.thomas.pt.utility.Utility
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.*
import java.nio.file.Path
import kotlin.math.E
import kotlin.math.ln
import kotlin.math.pow

// TODO: Finalize LOS calculation
/**
 * Level of Service (LOS) Calculator for public transit.
 * Calculates wait-ride score, pedestrian environment score, and final LOS grade.
 *
 * Formulas based on TCQSM (Transit Capacity and Quality of Service Manual).
 */
class LOSCalculator(configPath: Path) {
    private val config = Utility.loadYaml(configPath)
    private val scoringConfig = Utility.getYamlSubconfig(config, "scoring")
    private val waitRideConfig = Utility.getYamlSubconfig(scoringConfig, "wait_ride")
    private val amenityConfig = Utility.getYamlSubconfig(scoringConfig, "amenity")
    private val pedEnvConfig = Utility.getYamlSubconfig(scoringConfig, "ped_env")

    // Config values
    private val elasticity = (waitRideConfig["elas"] as Number).toDouble()
    private val baseTravelTime = (waitRideConfig["base_travel_time"] as Number).toDouble()

    private val shelterTime = (amenityConfig["shelter"] as Number).toDouble()
    private val shelterRate = (amenityConfig["shelter_rate"] as Number).toDouble()
    private val benchTime = (amenityConfig["bench"] as Number).toDouble()
    private val benchRate = (amenityConfig["bench_rate"] as Number).toDouble()

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
    private fun calculateAmenityTime(avgTripLengthKm: Double): Double {
        return (shelterTime * shelterRate + benchTime * benchRate) / avgTripLengthKm
    }

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
    fun calculateWaitRideScore(
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
    fun calculatePedestrianScore(vehFlow: Double, avgSpeedMps: Double): Double {
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
    fun calculateLOS(waitRideScore: Double, pedScore: Double): Double {
        return 6.0 - 1.5 * waitRideScore + 0.15 * pedScore
    }

    /**
     * LOS Grade: A-F based on score thresholds
     */
    fun calculateLOSGrade(los: Double): String = when {
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
    fun processDataFrame(df: AnyFrame, avgTripLengthM: Double): AnyFrame {
        return df
            .add("wait_ride_score") {
                val busFreq = "bus_frequency"<Double?>() ?: 1.0
                val lf = "avg_lf"<Double?>() ?: 0.5
                val busSpeed = "bus_link_avg_speed"<Double?>() ?: 5.0
                val ewt = "ewt"<Double?>() ?: 0.0
                calculateWaitRideScore(busFreq, lf, busSpeed, ewt, avgTripLengthM)
            }
            .add("ped_score") {
                val vehFlow = "veh_flow"<Double?>() ?: 0.0
                val avgSpeed = "avg_speed"<Double?>() ?: 10.0
                calculatePedestrianScore(vehFlow, avgSpeed)
            }
            .add("los") {
                calculateLOS("wait_ride_score"<Double>(), "ped_score"<Double>())
            }
            .add("los_grade") {
                calculateLOSGrade("los"<Double>())
            }
    }
}