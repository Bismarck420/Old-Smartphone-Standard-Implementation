package com.example.hostossi

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Entity(tableName = "modules")
@Serializable
data class Module(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo("project_id") var projectId: String = "",
    @ColumnInfo("display_order") var displayOrder: Int = 0,
    @SerialName("title")
    @ColumnInfo("module_title") var moduleTitle : String = "",
    @ColumnInfo("module_description") var description : String = "",
    @SerialName("type")
    @ColumnInfo("module_type") var moduleType : String = "",
    @ColumnInfo("value") var value: Double = 0.0,
    @ColumnInfo("unit") var unit: String = "",
    @SerialName("device_list")
    @ColumnInfo("device_list") var deviceList: MutableList<DeviceEntity> = mutableListOf()
) {
    @Ignore var action: Action? = null
}

@Serializable
data class Action(
    val type: String? = null,
    val message: String? = null,
    val url: String? = null,
    val method: String? = null,
    val body: Map<String, String>? = null
)
