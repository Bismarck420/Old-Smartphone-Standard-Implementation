package com.example.hostossi

object ProjectManager {
    val projectList = mutableListOf<Project>()
    var clientServerIP : String = ""
    var hostSelectedProject : Project = Project()
    var clientSelectedProject : Project = Project()

    fun findProject(id: String): Project? {
        return projectList.find { it.id == id }
    }

    /** Measurement values are transient: never write them through Room. */
    @Synchronized
    fun updateSensorValues(deviceId: String, values: List<Float>) {
        projectList.asSequence()
            .flatMap { it.moduleList.asSequence() }
            .flatMap { it.deviceList.asSequence() }
            .firstOrNull { it.id == deviceId }
            ?.values = values
    }

    @Synchronized
    fun sensorValuesSnapshot(): Map<String, List<Float>> = projectList
        .asSequence()
        .flatMap { it.moduleList.asSequence() }
        .flatMap { it.deviceList.asSequence() }
        .filter { it.values.isNotEmpty() }
        .associate { it.id to it.values.toList() }

}
