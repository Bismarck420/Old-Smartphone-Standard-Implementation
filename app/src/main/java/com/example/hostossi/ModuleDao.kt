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
interface ModuleDao {

    @Query("DELETE FROM modules")
    suspend fun deleteAll()

    @Query("SELECT * FROM modules")
    fun getAll(): Flow<List<Module>>

    @Query("SELECT * FROM modules WHERE id = :moduleId")
    suspend fun getModuleById(moduleId: String): Module?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModule(module: Module)

    @Update
    suspend fun updateModule(module: Module)

    @Delete
    suspend fun delete(module: Module)

    @Transaction
    @Query("SELECT * FROM modules WHERE id = :moduleID")
    fun getModuleWithDevices(moduleID: String): Flow<ModuleWithDevices?>
}

