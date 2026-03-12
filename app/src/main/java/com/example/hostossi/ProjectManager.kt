package com.example.hostossi

object ProjectManager {
    val projectList = mutableListOf<Project>()
    var clientServerIP : String = ""
    var hostSelectedProject : Project = Project()
    var clientSelectedProject : Project = Project()


    fun findProject(id: String): Project? {
        return projectList.find { it.id == id }
    }

}