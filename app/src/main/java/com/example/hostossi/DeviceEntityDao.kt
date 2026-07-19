package com.example.hostossi

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DeviceEntityDao {
    @Insert (onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(peripheral: DeviceEntity)

    @Query("SELECT * FROM devices")
    suspend fun getAllDevices(): List<DeviceEntity>

    @Query("DELETE FROM devices")
    suspend fun deleteAll()

    @Query("SELECT * FROM devices WHERE module_id = :moduleId")
    suspend fun getDevicesForModule(moduleId: String): List<DeviceEntity>

    @Query("DELETE FROM devices WHERE module_id = :moduleId")
    suspend fun deleteDevicesForModule(moduleId: String)

    @Update
    suspend fun updateDevice(device: DeviceEntity)

    @Delete
    suspend fun deleteDevice(device: DeviceEntity)
}
