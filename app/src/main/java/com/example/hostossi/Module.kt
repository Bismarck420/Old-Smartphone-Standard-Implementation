package com.example.hostossi

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.util.UUID
@Entity(tableName = "modules")
data class Module(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo("module_title") var moduleTitle : String = "",
    @ColumnInfo("module_description") var description : String = "",
    @ColumnInfo("peripheral_list") var peripheralList: MutableList<Peripheral> = mutableListOf(),
    @ColumnInfo("selected_peripherals") var selectedPeripherals : MutableList<Peripheral> = mutableListOf()
)
