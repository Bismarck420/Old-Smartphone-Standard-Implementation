package com.example.hostossi

import androidx.room.Embedded
import androidx.room.Relation

data class ManagerWithDevices(
    @Embedded val manager: Manager,
    @Relation(
        parentColumn = "id",
        entityColumn = "manager_id"
    )
    val devices: List<DeviceEntity>
) {

}