package com.example.hostossi

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(manager: Manager)

    @Query("SELECT * FROM managers WHERE id = :managerId")
    suspend fun getManagerById(managerId: String): Manager?

    @Query("SELECT * FROM managers")
    suspend fun getAllManagers(): List<Manager>

    @Query("DELETE FROM managers")
    suspend fun deleteAll()

    @Delete
    suspend fun deleteManager(manager: Manager)

    @Update
    suspend fun updateManager(manager: Manager)

    @Transaction
    @Query("SELECT * FROM managers WHERE id = :managerID")
    fun getManagerWithDevices(managerID: String): Flow<ManagerWithDevices?>

}