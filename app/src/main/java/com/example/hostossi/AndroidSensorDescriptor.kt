package com.example.hostossi

import kotlinx.serialization.Serializable

/** A selectable physical sensor. The numeric Android type prevents name-based misclassification. */
@Serializable
data class AndroidSensorDescriptor(
    val name: String,
    val sensorType: Int,
    val type: DeviceType,
    val unit: String = SensorPresentation.unitFor(type),
    val sourceDeviceId: String = "",
    val sourceDeviceName: String = "Android device"
)

object SensorPresentation {
    fun unitFor(type: DeviceType): String = when (type) {
        DeviceType.LIGHT_SENSOR -> "lx"
        DeviceType.PRESSURE -> "hPa"
        DeviceType.AMBIENT_TEMPERATURE, DeviceType.TEMPERATURE -> "°C"
        DeviceType.RELATIVE_HUMIDITY -> "%"
        DeviceType.STEP_COUNTER -> "steps"
        DeviceType.HEART_RATE -> "bpm"
        else -> ""
    }
}
