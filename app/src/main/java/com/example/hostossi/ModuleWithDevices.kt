package com.example.hostossi

import androidx.room.Embedded
import androidx.room.Relation

data class ModuleWithDevices(
    @Embedded val module: Module,
    @Relation(
        parentColumn = "id",
        entityColumn = "module_id"
        )
    val devices: List<DeviceEntity>

)
