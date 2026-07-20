package com.example.hostossi

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Entity(tableName = "projects")
@Serializable
data class Project(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name="peripheral_count") var peripCount: Int = 0,
    @ColumnInfo(name="name") var name: String = "",
    @ColumnInfo(name="display_order") var displayOrder: Int = 0,
    @SerialName("widgets")
    @ColumnInfo(name="module_list") var moduleList: MutableList<Module> = mutableListOf(),
    @ColumnInfo(name="project_description") var description: String = "",
)
