package com.example.hostossi

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PeripheralDao {
    @Insert (onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeripheral(peripheral: Peripheral)

    @Query("SELECT * FROM peripherals")
    suspend fun getAllPeripherals(): List<Peripheral>

    @Query("DELETE FROM peripherals")
    suspend fun deleteAll()

    @Query("SELECT * FROM peripherals WHERE module_id = :moduleId")
    suspend fun getPeripheralsForModule(moduleId: String): List<Peripheral>

    @Update
    suspend fun updatePeripheral(peripheral: Peripheral)

    @Delete
    suspend fun deletePeripheral(peripheral: Peripheral)
}
