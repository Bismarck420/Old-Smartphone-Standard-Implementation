package com.example.hostossi

import androidx.room.Embedded
import androidx.room.Relation

data class ProjectWithModules(
    @Embedded val project: Project,
    @Relation(
        entity = Module::class,
        parentColumn = "id",
        entityColumn = "project_id"
    )
    val modules: List<ModuleWithDevices>
)
