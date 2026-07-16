package com.example.hostossi

import androidx.room.Embedded
import androidx.room.Relation

data class ModuleWithPeripherals(
    @Embedded val module: Module,
    @Relation(
        parentColumn = "id",
        entityColumn = "module_id"
        )
    val peripherals: List<Peripheral>

)
