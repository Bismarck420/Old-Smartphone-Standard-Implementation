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
    fun peripheralListToString(peripheralList: MutableList<Peripheral>) : String{
        return gson.toJson(peripheralList)
    }
    @TypeConverter
    fun stringToPeripheralList(string: String) : MutableList<Peripheral>{
        if (string == null) return mutableListOf()
        val listType = object : com.google.gson.reflect.TypeToken<MutableList<Peripheral>>() {}.type
        return gson.fromJson(string, listType)
    }

}