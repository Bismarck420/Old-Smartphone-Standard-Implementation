package com.example.hostossi

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

enum class moduleType{
    GENERIC,
    SWITCH,
    EMPTY
}

@Entity(tableName = "modules")
@Serializable
data class Module(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo("module_title") var moduleTitle : String = "",
    @ColumnInfo("module_description") var description : String = "",
    @ColumnInfo("peripheral_list") var peripheralList: MutableList<Peripheral> = mutableListOf(),
    @ColumnInfo("selected_peripherals") var selectedPeripherals : MutableList<Peripheral> = mutableListOf(),
    @ColumnInfo("module_type") var moduleType : moduleType

)

