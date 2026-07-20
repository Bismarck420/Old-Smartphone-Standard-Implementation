package com.example.hostossi

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionType {
    ANDROID,
    WIFI,
    MQTT,
    BLUETOOTH
}