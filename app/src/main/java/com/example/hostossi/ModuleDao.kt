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

    @Query("SELECT * FROM modules ORDER BY display_order, module_title")
    fun getAll(): Flow<List<Module>>

    @Query("SELECT * FROM modules WHERE id = :moduleId")
    suspend fun getModuleById(moduleId: String): Module?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModule(module: Module)

    @Update
    suspend fun updateModule(module: Module)

    @Query("UPDATE modules SET display_order = :displayOrder WHERE id = :moduleId")
    suspend fun updateDisplayOrder(moduleId: String, displayOrder: Int)

    @Transaction
    suspend fun updateModuleOrder(modules: List<Module>) {
        modules.forEachIndexed { index, module -> updateDisplayOrder(module.id, index) }
    }

    @Delete
    suspend fun delete(module: Module)

    @Transaction
    @Query("SELECT * FROM modules WHERE id = :moduleID")
    fun getModuleWithDevices(moduleID: String): Flow<ModuleWithDevices?>
}

