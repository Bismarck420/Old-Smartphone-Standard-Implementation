package com.example.hostossi

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagerInventoryTest {
    private val sensorDevice = DeviceEntity(
        moduleId = "module-1",
        managerID = "manager-1",
        managerPeripheralId = "temperature-1",
        name = "Temperature",
        type = DeviceType.AMBIENT_TEMPERATURE,
        connectionType = ConnectionType.WIFI
    )

    @Test
    fun assignedSensorIsAvailableWhileStillAdvertised() {
        val manager = managerWith(
            AdvertisedPeripheral("temperature-1", "sensor", "temperature", "Temperature")
        )

        assertEquals(
            ManagedEndpointState.AVAILABLE,
            sensorDevice.managedEndpointState(mapOf(manager.managerId to manager))
        )
    }

    @Test
    fun assignedSensorIsRemovedWhenManagerDropsItsChannel() {
        val manager = managerWith(
            AdvertisedPeripheral("relay-1", "switch", "relay", "Relay")
        )

        assertEquals(
            ManagedEndpointState.CHANNEL_REMOVED,
            sensorDevice.managedEndpointState(mapOf(manager.managerId to manager))
        )
    }

    @Test
    fun assignedSensorDetectsWhenSameChannelBecomesSwitch() {
        val manager = managerWith(
            AdvertisedPeripheral("temperature-1", "switch", "relay", "Repurposed relay")
        )

        assertEquals(
            ManagedEndpointState.TYPE_CHANGED,
            sensorDevice.managedEndpointState(mapOf(manager.managerId to manager))
        )
    }

    @Test
    fun assignedSensorDetectsOfflineManager() {
        assertEquals(
            ManagedEndpointState.MANAGER_OFFLINE,
            sensorDevice.managedEndpointState(emptyMap())
        )
    }

    private fun managerWith(vararg devices: AdvertisedPeripheral) = AdvertisedManager(
        managerId = "manager-1",
        title = "Room manager",
        ipAddress = "192.168.1.20",
        devices = devices.toList(),
        lastSeenAt = 1L
    )
}
