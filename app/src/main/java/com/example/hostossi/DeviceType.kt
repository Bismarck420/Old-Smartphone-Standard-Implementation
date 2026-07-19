package com.example.hostossi

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType {
    LIGHT_SENSOR,
    ACCELEROMETER,
    GYROSCOPE,
    MAGNETIC_FIELD,
    PROXIMITY,
    PRESSURE,
    AMBIENT_TEMPERATURE,
    RELATIVE_HUMIDITY,
    GRAVITY,
    LINEAR_ACCELERATION,
    ROTATION_VECTOR,
    STEP_COUNTER,
    HEART_RATE,
    STATIONARY_DETECT,
    MOTION_DETECT,
    HEART_BEAT,

    SWITCH,
    UNKNOWN;

    companion object {
        fun fromString(name: String): DeviceType {
            val upperName = name.uppercase()
            return when {
                upperName.contains("LIGHT") -> LIGHT_SENSOR
                upperName.contains("ACCELEROMETER") || upperName.contains("ACCEL") -> ACCELEROMETER
                upperName.contains("GYROSCOPE") || upperName.contains("GYRO") -> GYROSCOPE
                upperName.contains("MAGNETIC") || upperName.contains("MAG") -> MAGNETIC_FIELD
                upperName.contains("PROXIMITY") -> PROXIMITY
                upperName.contains("PRESSURE") || upperName.contains("BAROMETER") -> PRESSURE
                upperName.contains("TEMPERATURE") -> AMBIENT_TEMPERATURE
                upperName.contains("HUMIDITY") -> RELATIVE_HUMIDITY
                upperName.contains("GRAVITY") -> GRAVITY
                upperName.contains("LINEAR ACCEL") -> LINEAR_ACCELERATION
                upperName.contains("ROTATION VECTOR") -> ROTATION_VECTOR
                upperName.contains("STEP COUNTER") -> STEP_COUNTER
                upperName.contains("HEART RATE") -> HEART_RATE
                upperName.contains("STATIONARY") -> STATIONARY_DETECT
                upperName.contains("MOTION") -> MOTION_DETECT
                upperName.contains("HEART BEAT") -> HEART_BEAT
                upperName.contains("SWITCH") || upperName.contains("RELAY") -> SWITCH
                else -> UNKNOWN
            }
        }
    }
}
