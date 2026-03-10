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
interface ProjectDao {

@Query("SELECT * FROM projects")
fun getAll(): Flow<List<Project>>

@Insert
suspend fun insertAll(vararg project: Project)

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertProject(project: Project)

@Update
suspend fun updateProject(project: Project)

    @Query("UPDATE projects SET is_selected_project = 0")
    suspend fun resetAllSelection()

    @Query("UPDATE projects SET is_selected_project = 1 WHERE id = :projectId")
    suspend fun setSelected(projectId: String)

    @Transaction
    suspend fun updateSelectedProject(projectId: String) {
        resetAllSelection()
        setSelected(projectId)
    }


@Delete
suspend fun delete(project: Project)
}
