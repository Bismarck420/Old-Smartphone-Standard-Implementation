package com.example.hostossi

import android.hardware.Sensor
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
    ORIENTATION,
    TEMPERATURE,
    ACCELEROMETER_UNCALIBRATED,
    GYROSCOPE_UNCALIBRATED,
    MAGNETIC_FIELD_UNCALIBRATED,
    GAME_ROTATION_VECTOR,
    GEOMAGNETIC_ROTATION_VECTOR,
    STEP_DETECTOR,
    SIGNIFICANT_MOTION,
    TILT_DETECTOR,
    WAKE_GESTURE,
    GLANCE_GESTURE,
    PICK_UP_GESTURE,
    DEVICE_ORIENTATION,
    POSE_6DOF,
    STATIONARY_DETECT,
    MOTION_DETECT,
    HEART_BEAT,

    SWITCH,
    UNKNOWN;

    companion object {
        fun fromAndroidSensorType(sensorType: Int): DeviceType = when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> ACCELEROMETER
            Sensor.TYPE_MAGNETIC_FIELD -> MAGNETIC_FIELD
            Sensor.TYPE_ORIENTATION -> ORIENTATION
            Sensor.TYPE_GYROSCOPE -> GYROSCOPE
            Sensor.TYPE_LIGHT -> LIGHT_SENSOR
            Sensor.TYPE_PRESSURE -> PRESSURE
            Sensor.TYPE_TEMPERATURE -> TEMPERATURE
            Sensor.TYPE_PROXIMITY -> PROXIMITY
            Sensor.TYPE_GRAVITY -> GRAVITY
            Sensor.TYPE_LINEAR_ACCELERATION -> LINEAR_ACCELERATION
            Sensor.TYPE_ROTATION_VECTOR -> ROTATION_VECTOR
            Sensor.TYPE_RELATIVE_HUMIDITY -> RELATIVE_HUMIDITY
            Sensor.TYPE_AMBIENT_TEMPERATURE -> AMBIENT_TEMPERATURE
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> MAGNETIC_FIELD_UNCALIBRATED
            Sensor.TYPE_GAME_ROTATION_VECTOR -> GAME_ROTATION_VECTOR
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> GYROSCOPE_UNCALIBRATED
            Sensor.TYPE_SIGNIFICANT_MOTION -> SIGNIFICANT_MOTION
            Sensor.TYPE_STEP_DETECTOR -> STEP_DETECTOR
            Sensor.TYPE_STEP_COUNTER -> STEP_COUNTER
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> GEOMAGNETIC_ROTATION_VECTOR
            Sensor.TYPE_HEART_RATE -> HEART_RATE
            22 -> TILT_DETECTOR // TYPE_TILT_DETECTOR
            23 -> WAKE_GESTURE // TYPE_WAKE_GESTURE
            24 -> GLANCE_GESTURE // TYPE_GLANCE_GESTURE
            25 -> PICK_UP_GESTURE // TYPE_PICK_UP_GESTURE
            26, 27 -> DEVICE_ORIENTATION // wrist tilt and device orientation
            Sensor.TYPE_POSE_6DOF -> POSE_6DOF
            Sensor.TYPE_STATIONARY_DETECT -> STATIONARY_DETECT
            Sensor.TYPE_MOTION_DETECT -> MOTION_DETECT
            Sensor.TYPE_HEART_BEAT -> HEART_BEAT
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> ACCELEROMETER_UNCALIBRATED
            else -> UNKNOWN
        }

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
