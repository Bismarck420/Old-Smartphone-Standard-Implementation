package com.example.hostossi

import android.R
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "peripherals")
data class Peripheral(
    @PrimaryKey val peripheralID: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "peripheral_name") var peripheralName: String = "",
    @ColumnInfo(name = "peripheral_type") var peripheralType: String = "",
    @ColumnInfo(name = "connection_type") var connectionType: String = "",
    @ColumnInfo(name="peripheral_description") var peripheralDescription: String = ""
)
