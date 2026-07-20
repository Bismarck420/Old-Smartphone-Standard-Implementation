package com.example.hostossi

import androidx.room.TypeConverter
import com.google.gson.Gson

class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromDeviceType(type: DeviceType): String =
        type.name

    @TypeConverter
    fun toDeviceType(value: String): DeviceType = try {
        DeviceType.valueOf(value)
    } catch (e: Exception) {
        DeviceType.UNKNOWN
    }

    @TypeConverter
    fun fromConnectionType(type: ConnectionType): String =
        type.name

    @TypeConverter
    fun toConnectionType(value: String): ConnectionType = try {
        ConnectionType.valueOf(value)
    } catch (e: Exception) {
        ConnectionType.ANDROID
    }

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
    fun fromDeviceEntityList(list: MutableList<DeviceEntity>): String {
        return gson.toJson(list)
    }

    @TypeConverter
    fun toDeviceEntityList(data: String): MutableList<DeviceEntity> {
        if (data == null) return mutableListOf()
        val listType = object : com.google.gson.reflect.TypeToken<MutableList<DeviceEntity>>() {}.type
        return gson.fromJson(data, listType)
    }

    @TypeConverter
    fun doubleListToString(list: List<Float>): String {
        return gson.toJson(list)
    }

    @TypeConverter
    fun stringToDoubleList(data: String): List<Float> {
        if (data == null) return emptyList()
        val listType = object : com.google.gson.reflect.TypeToken<List<Float>>() {}.type
        return gson.fromJson(data, listType)
    }

}
