package com.example.hostossi

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Entity(tableName = "managers")
@Serializable
data class Manager (
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo("manager_id") var managerID: String = "",
    @ColumnInfo("title") var title: String = "",
    @SerialName("device_list")
    @ColumnInfo("device_list") var deviceList: MutableList<DeviceEntity> = mutableListOf(),
    @ColumnInfo("ip_address") var ipAddress: String = ""
)
