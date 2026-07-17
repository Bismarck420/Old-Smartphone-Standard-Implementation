package com.example.hostossi

import androidx.room.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Entity(tableName = "devices")
@Serializable
data class DeviceEntity(

    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo("module_id") val moduleId: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("description") val description: String = "",
    @ColumnInfo("type") val type: DeviceType,
    @ColumnInfo("connectionType") val connectionType: ConnectionType,
    @ColumnInfo("ipAddress") val ipAddress: String = ""

)