package com.thomas.pt.data.scoring

enum class AggregationMode {
    OPERATOR_VEH_TIME, OPERATOR_LOAD, PASSENGER_TIME, PASSENGER_TRIP;

    override fun toString(): String = when (this) {
        OPERATOR_VEH_TIME -> "Operator Vehicle Time"
        OPERATOR_LOAD -> "Operator Load"
        PASSENGER_TIME -> "Passenger Time"
        PASSENGER_TRIP -> "Passenger Trip"
    }
}