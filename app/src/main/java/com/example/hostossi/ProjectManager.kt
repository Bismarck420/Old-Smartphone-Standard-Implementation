package com.example.hostossi

object ProjectManager {
    val projectList = mutableListOf<Project>()
    var clientServerIP : String = ""
    var hostSelectedProject : Project = Project()
    var clientSelectedProject : Project = Project()

    fun findProject(id: String): Project? {
        return projectList.find { it.id == id }
    }

    @Synchronized
    fun replaceProjects(projects: List<Project>) {
        projectList.clear()
        projectList.addAll(projects)
    }

    @Synchronized
    fun clearProjects() {
        projectList.clear()
        clientSelectedProject = Project()
    }

    @Synchronized
    fun projectsSnapshot(): List<Project> = projectList.toList()

    /** Measurement values are transient: never write them through Room. */
    @Synchronized
    fun updateSensorValues(deviceId: String, values: List<Float>): Boolean {
        val device = projectList.asSequence()
            .flatMap { it.moduleList.asSequence() }
            .flatMap { it.deviceList.asSequence() }
            .firstOrNull { it.id == deviceId }
            ?: return false
        device.values = values.toList()
        return true
    }

    @Synchronized
    fun sensorValuesSnapshot(): Map<String, List<Float>> = projectList
        .asSequence()
        .flatMap { it.moduleList.asSequence() }
        .flatMap { it.deviceList.asSequence() }
        .filter { it.values.isNotEmpty() }
        .associate { it.id to it.values.toList() }

    /** Clears stale readings for manager channels that disappeared or changed type. */
    @Synchronized
    fun reconcileManagerInventory(managers: List<AdvertisedManager>): Int {
        val managersById = managers.associateBy { it.managerId }
        var invalidated = 0
        projectList.asSequence()
            .flatMap { it.moduleList.asSequence() }
            .flatMap { it.deviceList.asSequence() }
            .filter { it.managerID.isNotBlank() }
            .forEach { device ->
                if (device.managedEndpointState(managersById) != ManagedEndpointState.AVAILABLE &&
                    device.values.isNotEmpty()
                ) {
                    device.values = emptyList()
                    invalidated++
                }
            }
        return invalidated
    }

}
