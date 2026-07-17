package com.example.hostossi

import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson

class Converters {

    private val gson = Gson()

    @TypeConverter
    fun mutableModuleListToString(moduleList: MutableList<Module>) : String{
        return gson.toJson(moduleList)
    }
    @TypeConverter
    fun stringToMutableModuleList(string: String) : MutableList<Module>{
        if (string == null) return mutableListOf()
        val listType = object : com.google.gson.reflect.TypeToken<MutableList<Module>>() {}.type
        return gson.fromJson(string, listType)
    }

    @TypeConverter
    fun fromDeviceType(type: DeviceType): String =
        type.name

    @TypeConverter
    fun toDeviceType(value: String): DeviceType =
        DeviceType.valueOf(value)

    @TypeConverter
    fun fromConnectionType(type: ConnectionType): String =
        type.name

    @TypeConverter
    fun toConnectionType(value: String): ConnectionType =
        ConnectionType.valueOf(value)

    @TypeConverter
    fun fromDeviceEntityList(list: MutableList<DeviceEntity>): String {
        return gson.toJson(list)
    }

    @TypeConverter
    fun toDeviceEntityList(data: String): MutableList<DeviceEntity> {
        val listType = object : com.google.gson.reflect.TypeToken<MutableList<DeviceEntity>>() {}.type
        return gson.fromJson(data, listType)
    }

}