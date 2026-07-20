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

@Query("SELECT * FROM projects ORDER BY display_order, name")
fun getAll(): Flow<List<Project>>

@Query("SELECT * FROM projects ORDER BY display_order, name")
suspend fun getAllOnce(): List<Project>

@Transaction
@Query("SELECT * FROM projects ORDER BY display_order, name")
fun getAllWithModules(): Flow<List<ProjectWithModules>>

@Transaction
@Query("SELECT * FROM projects ORDER BY display_order, name")
suspend fun getAllWithModulesOnce(): List<ProjectWithModules>

@Transaction
@Query("SELECT * FROM projects WHERE id = :projectId")
fun getProjectWithModules(projectId: String): Flow<ProjectWithModules?>

@Insert
suspend fun insertAll(vararg project: Project)

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertProject(project: Project)

@Update
suspend fun updateProject(project: Project)

@Query("UPDATE projects SET display_order = :displayOrder WHERE id = :projectId")
suspend fun updateDisplayOrder(projectId: String, displayOrder: Int)

@Transaction
suspend fun updateProjectOrder(projects: List<Project>) {
    projects.forEachIndexed { index, project -> updateDisplayOrder(project.id, index) }
}

@Delete
suspend fun delete(project: Project)
}
