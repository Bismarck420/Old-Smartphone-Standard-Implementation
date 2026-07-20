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
    @ColumnInfo("connection_type") val connectionType: ConnectionType,
    @ColumnInfo("ip_address") val ipAddress: String = "",
    /** Android's stable Sensor.TYPE_* value. -1 is used for non-phone devices. */
    @ColumnInfo("sensor_type") val sensorType: Int = -1,
    @ColumnInfo("values") var values: List<Float> = emptyList(),
    @ColumnInfo("unit") var unit: String = ""

)
