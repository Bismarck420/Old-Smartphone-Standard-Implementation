package com.example.hostossi

import android.app.ProgressDialog
import android.content.Context
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import java.io.InputStream

@Serializable
data class ProjectsResponse(
    val projects: List<Project>,
    val sourceDeviceId: String = ""
)

@Serializable
data class ToggleRequest(val projectId: String, val moduleId: String, val value: Boolean)

@Serializable
data class SensorValuesPayload(val values: Map<String, List<Float>>)

@Serializable
data class ManagersResponse(val managers: List<AdvertisedManager>)

object KtorServer {
    private const val TAG = "KtorServer"
    private const val SENSOR_TAG = "SensorStream"
    private const val SENSOR_BATCH_INTERVAL_MS = 120L
    private const val SENSOR_RETRY_DELAY_MS = 500L

    private var client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 2_500
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
    }

    private var serverJob: Job? = null
    private var clientJob: Job? = null

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sensorData = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeHostSensors = mutableMapOf<String, MeasureableSensor>()
    private val activeClientSensors = mutableMapOf<String, MeasureableSensor>()
    private val sensorUploadLock = Any()
    private val pendingSensorValues = mutableMapOf<String, List<Float>>()
    private var sensorUploadJob: Job? = null
    @Volatile private var lastProjectSyncAt: Long = 0L

    fun isRunning(): Boolean = serverJob?.isActive == true
    fun activeClientSensorCount(): Int = activeClientSensors.size
    fun lastProjectSyncTimestamp(): Long = lastProjectSyncAt

    /** Clears Host-era in-memory data when the user explicitly switches this device to Client mode. */
    fun resetForClientMode() {
        stopHostSensorListeners()
        mainHandler.post {
            activeClientSensors.values.forEach { it.stopListening() }
            activeClientSensors.clear()
        }
        ProjectManager.clearProjects()
        lastProjectSyncAt = 0L
        Log.d(TAG, "Client project state reset; waiting for Host synchronization")
    }

    /** Executes a room-control action directly from the native client dashboard. */
    suspend fun controlClientSwitch(moduleId: String, enabled: Boolean): Result<Int> = runCatching {
        val module = ProjectManager.projectList.asSequence()
            .flatMap { it.moduleList.asSequence() }
            .firstOrNull { it.id == moduleId }
            ?: error("Switch module is no longer available")

        managedEndpointFailure(module.deviceList)?.let { (_, state) ->
            error(state.explanation)
        }
        val targetCount = sendSwitchCommand(module.deviceList, enabled)
        if (targetCount == 0) error("No ESP8266 endpoint configured")
        module.value = if (enabled) 1.0 else 0.0
        targetCount
    }

    fun startServer(context: Context) {
        if (serverJob != null) return
        ManagerDiscovery.start()
        serverJob = serverScope.launch {
            try {
                Log.d("test", "server starting")
                embeddedServer(Netty, port = 8080) {
                    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                        json()
                    }

                    routing {
                        post("/selectedProject") {
                            try {
                                val project = call.receive<Project>()
                                ProjectManager.clientSelectedProject = project
                                Log.d(TAG, "Selected project received: ${project.name}")
                                call.respondText("selected Project received")
                            } catch (e: Exception) {
                                Log.e(TAG, "Mapping /selectedProject failed", e)
                                call.respondText("Error: ${e.message}", status = HttpStatusCode.BadRequest)
                            }
                        }

                        post("/syncProjects") {
                            try {
                                val response = call.receive<ProjectsResponse>()
                                val deviceMode = PreferenceManager.getDefaultSharedPreferences(context)
                                    .getString("deviceMode", "host")
                                if (deviceMode != "client") {
                                    call.respondText("Project sync is only accepted in Client mode", status = HttpStatusCode.Conflict)
                                    return@post
                                }
                                if (response.sourceDeviceId.isNotBlank() &&
                                    response.sourceDeviceId == DeviceIdentity.id(context)
                                ) {
                                    Log.w(TAG, "Rejected project sync originating from this Client device")
                                    call.respondText("Self-originated project sync rejected", status = HttpStatusCode.Conflict)
                                    return@post
                                }
                                Log.d(TAG, "Received ${response.projects.size} projects via /syncProjects")
                                ProjectManager.replaceProjects(response.projects)
                                lastProjectSyncAt = System.currentTimeMillis()
                                startClientSensorListeners(context)
                                call.respondText("Projects synced")
                            } catch (e: Exception) {
                                Log.e(TAG, "Mapping /syncProjects failed", e)
                                call.respondText("Sync failed: ${e.message}", status = HttpStatusCode.BadRequest)
                            }
                        }

                        post("/sensor-values") {
                            try {
                                val payload = call.receive<SensorValuesPayload>()
                                val unknownDeviceIds = payload.values.mapNotNull { (deviceId, values) ->
                                    deviceId.takeUnless {
                                        ProjectManager.updateSensorValues(deviceId, values)
                                    }
                                }
                                if (unknownDeviceIds.isEmpty()) {
                                    Log.v(SENSOR_TAG, "Client applied ${payload.values.size} sensor value(s)")
                                } else {
                                    Log.w(
                                        SENSOR_TAG,
                                        "Client ignored values for unknown sensor IDs: ${unknownDeviceIds.joinToString()}"
                                    )
                                }
                                call.respondText("Sensor values synced")
                            } catch (e: Exception) {
                                Log.e(TAG, "Sensor value sync failed", e)
                                call.respondText("Invalid sensor values", status = HttpStatusCode.BadRequest)
                            }
                        }

                        // Legacy route remains available to older dashboard builds.
                        post("/toggleSwitch") { handleSwitchRequest(call, context, clientOnly = false) }

                        // The current dashboard always calls the Client proxy. The Client then
                        // reaches the ESP8266 over the room's local network.
                        post("/client/toggleSwitch") { handleSwitchRequest(call, context, clientOnly = true) }

                        get("/discovery") {
                            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                            val deviceMode = sharedPreferences.getString("deviceMode", "default")
                            
                            if (deviceMode == "client") {
                                call.respond(
                                    NetworkDiscovery.DiscoveryResponse(
                                        tailscaleAddress = TailscaleIntegration.status(context).ipv4Address
                                    )
                                )
                            } else {
                                // Hosts should not identify as clients for discovery
                                call.respondText("hostossi-host", status = HttpStatusCode.OK)
                            }
                        }

                        get("/sensors") {
                            try {
                                val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                                val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
                                val sourceDeviceId = DeviceIdentity.id(context)
                                val sourceDeviceName = DeviceIdentity.name()
                                call.respond(deviceSensors.map { sensor ->
                                    AndroidSensorDescriptor(
                                        name = sensor.name,
                                        sensorType = sensor.type,
                                        type = DeviceType.fromAndroidSensorType(sensor.type),
                                        sourceDeviceId = sourceDeviceId,
                                        sourceDeviceName = sourceDeviceName
                                    )
                                })
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to get sensors", e)
                                call.respondText("Error fetching sensors", status = HttpStatusCode.InternalServerError)
                            }
                        }

                        get("/managers") {
                            val deviceMode = PreferenceManager.getDefaultSharedPreferences(context)
                                .getString("deviceMode", "default")
                            if (deviceMode != "client") {
                                call.respondText("Manager discovery is only available on a Client device", status = HttpStatusCode.Conflict)
                                return@get
                            }
                            val managers = ManagerDiscovery.snapshot()
                            val invalidated = ProjectManager.reconcileManagerInventory(managers)
                            if (invalidated > 0) {
                                Log.i(TAG, "Cleared stale values for $invalidated removed manager channel(s)")
                            }
                            Log.v(TAG, "GET /managers returning ${managers.size} manager(s)")
                            call.respond(ManagersResponse(managers))
                        }

                        get("/") {
                            call.respondDashboardIndex(context)
                        }

                        get("/tasks") {
                            call.respondDashboardIndex(context)
                        }

                        get("/projects.json") {
                            try {
                                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                                val deviceMode = sharedPreferences.getString("deviceMode", "default")
                                
                                if (deviceMode == "client") {
                                    val synchronizedProjects = if (lastProjectSyncAt == 0L) {
                                        emptyList()
                                    } else {
                                        ProjectManager.projectsSnapshot()
                                    }
                                    call.respond(ProjectsResponse(synchronizedProjects))
                                } else if (deviceMode == "viewer") {
                                    call.respond(ProjectsResponse(emptyList()))
                                } else {
                                    // Host serves the website: we MUST provide the modules and devices
                                    // We use the in-memory list as it's the most up-to-date for live sensors
                                    call.respond(ProjectsResponse(ProjectManager.projectsSnapshot()))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in /projects.json", e)
                                call.respondText("Error: ${e.localizedMessage}", status = HttpStatusCode.InternalServerError)
                            }
                        }

                        get("/sensor-values.json") {
                            call.respond(SensorValuesPayload(ProjectManager.sensorValuesSnapshot()))
                        }

                        get("/favicon.svg") {
                            call.respondDashboardAsset(context, "dashboard/favicon.svg")
                        }

                        get("/assets/{path...}") {
                            val assetPath = call.parameters.getAll("path")?.joinToString("/")
                            if (assetPath.isNullOrBlank()) {
                                call.respondText("Not found", status = HttpStatusCode.NotFound)
                                return@get
                            }
                            call.respondDashboardAsset(context, "dashboard/assets/$assetPath")
                        }

                        get("/script.js") {
                            call.respondDashboardAsset(context, "dashboard/script.js")
                        }

                        get("/css/templatemo-crypto-dashboard.css") {
                            val css = context.assets.open("css/templatemo-crypto-dashboard.css")
                                .bufferedReader().use { it.readText() }
                            call.respondText(css, ContentType.Text.CSS)
                        }

                        get("/sensorData") {

                            val sensorData = call.receiveText()
                            call.respondText { "sensor data received" }
                        }
                    }
                }.start(wait = true)
                Log.d("test", "server started")
            } catch (ex: Exception) {
                Log.e("test", "failed to start server", ex)
            }
        }
    }

    fun stopServer() {
        ManagerDiscovery.stop()
        mainHandler.post {
            activeClientSensors.values.forEach { it.stopListening() }
            activeClientSensors.clear()
        }
        serverJob?.cancel()
        serverJob = null
        Log.d(TAG, "Server stopped")
    }

    /**
     * The client owns the phone sensors. It starts listeners only after the host has
     * sent a project configuration, and writes readings into the client-side live store.
     */
    private fun startClientSensorListeners(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getString("deviceMode", "default") != "client") return

        val applicationContext = context.applicationContext
        val localDeviceId = DeviceIdentity.id(applicationContext)
        val devices = ProjectManager.projectsSnapshot()
            .asSequence()
            .flatMap { it.moduleList.asSequence() }
            .flatMap { it.deviceList.asSequence() }
            .filter { it.connectionType == ConnectionType.ANDROID }
            .filter { device ->
                // Empty is the legacy representation and historically belonged to the client.
                device.sourceDeviceId.isBlank() || device.sourceDeviceId == localDeviceId
            }
            .associateBy { it.id }

        mainHandler.post {
            activeClientSensors.keys.toList()
                .filter { it !in devices }
                .forEach { deviceId -> activeClientSensors.remove(deviceId)?.stopListening() }

            devices.forEach { (deviceId, device) ->
                if (deviceId in activeClientSensors) return@forEach
                val sensor = DeviceFactory.create(applicationContext, device)
                if (sensor == null) {
                    Log.w(SENSOR_TAG, "Client cannot create sensor ${device.name} ($deviceId)")
                    return@forEach
                }
                if (!sensor.doesSensorExist) {
                    Log.w(SENSOR_TAG, "Client does not provide sensor ${device.name} ($deviceId)")
                    return@forEach
                }
                sensor.let {
                    sensor.setOnSensorValuesChangedListener { values ->
                        if (!ProjectManager.updateSensorValues(deviceId, values)) {
                            Log.w(SENSOR_TAG, "Client sample has no matching sensor: $deviceId")
                        }
                    }
                    sensor.startListening()
                    activeClientSensors[deviceId] = sensor
                    Log.d(SENSOR_TAG, "Started Client sensor ${device.name} ($deviceId)")
                }
            }
        }
    }

    /**
     * Keeps Host-phone sensors alive independently of DetailActivity. Only sensors explicitly
     * assigned to this Android device are sampled; Client-owned and Wi-Fi devices are excluded.
     */
    fun refreshHostSensorListeners(context: Context) {
        val applicationContext = context.applicationContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (preferences.getString("deviceMode", "host") != "host") {
            stopHostSensorListeners()
            return
        }

        val localDeviceId = DeviceIdentity.id(applicationContext)
        val devices = ProjectManager.projectsSnapshot()
            .asSequence()
            .flatMap { it.moduleList.asSequence() }
            .flatMap { it.deviceList.asSequence() }
            .filter { it.connectionType == ConnectionType.ANDROID }
            .filter { it.sourceDeviceId == localDeviceId }
            .associateBy { it.id }

        mainHandler.post {
            activeHostSensors.keys.toList()
                .filter { it !in devices }
                .forEach { deviceId ->
                    activeHostSensors.remove(deviceId)?.stopListening()
                    Log.d(SENSOR_TAG, "Stopped removed Host sensor $deviceId")
                }

            devices.forEach { (deviceId, device) ->
                if (deviceId in activeHostSensors) return@forEach
                val sensor = DeviceFactory.create(applicationContext, device)
                if (sensor == null) {
                    Log.w(SENSOR_TAG, "Host cannot create sensor ${device.name} ($deviceId)")
                    return@forEach
                }
                if (!sensor.doesSensorExist) {
                    Log.w(SENSOR_TAG, "Host does not provide sensor ${device.name} ($deviceId)")
                    return@forEach
                }

                sensor.setOnSensorValuesChangedListener { values ->
                    if (ProjectManager.updateSensorValues(deviceId, values)) {
                        syncSensorValuesToClient(applicationContext, deviceId, values)
                    } else {
                        Log.w(SENSOR_TAG, "Host sample has no matching sensor: $deviceId")
                    }
                }
                sensor.startListening()
                activeHostSensors[deviceId] = sensor
                Log.d(SENSOR_TAG, "Started Host sensor ${device.name} ($deviceId)")
            }
        }
    }

    fun stopHostSensorListeners() {
        synchronized(sensorUploadLock) {
            pendingSensorValues.clear()
            sensorUploadJob?.cancel()
            sensorUploadJob = null
        }
        mainHandler.post {
            activeHostSensors.values.forEach { it.stopListening() }
            activeHostSensors.clear()
            Log.d(SENSOR_TAG, "Stopped all Host sensors")
        }
    }

    fun sendSelectedProject(context: Context) {
        clientScope.launch {
            val result = ClientEndpointResolver.withFallback(context) { endpoint ->
                Log.d(TAG, "Sending selected project through ${endpoint.transport}: ${endpoint.address}")
                client.post("http://${endpoint.address}:8080/selectedProject") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectManager.hostSelectedProject)
                }
            }
            result.exceptionOrNull()?.let { Log.e(TAG, "Failed to send selected project", it) }
        }
    }

    suspend fun fetchAdvertisedManagers(
        context: Context,
        logResult: Boolean = true
    ): Result<List<AdvertisedManager>> {
        val result = ClientEndpointResolver.withFallback(context) { endpoint ->
            if (logResult) {
                Log.d(TAG, "Fetching managers from Client ${endpoint.address} via ${endpoint.transport}")
            }
            client.get("http://${endpoint.address}:8080/managers").body<ManagersResponse>().managers
        }
        if (logResult) {
            result.onSuccess { Log.d(TAG, "Host received ${it.size} advertised manager(s)") }
                .onFailure { Log.e(TAG, "Host failed to fetch advertised managers", it) }
        }
        return result
    }

    fun syncProjectsToClient(context: Context) {
        val deviceMode = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("deviceMode", "host")
        if (deviceMode != "host") {
            Log.d(TAG, "Ignored project upload from $deviceMode mode")
            return
        }
        refreshHostSensorListeners(context)
        clientScope.launch {
            val activeProjects = ProjectManager.projectsSnapshot()
            val result = ClientEndpointResolver.withFallback(context) { endpoint ->
                Log.d(TAG, "Syncing projects through ${endpoint.transport}: ${endpoint.address}")
                client.post("http://${endpoint.address}:8080/syncProjects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectsResponse(activeProjects, DeviceIdentity.id(context)))
                }
            }
            result.onSuccess {
                // Re-send every latest sample after the Client knows the device IDs. This closes
                // the startup race for slow or one-shot sensors whose first value arrived early.
                ProjectManager.sensorValuesSnapshot().forEach { (deviceId, values) ->
                    syncSensorValuesToClient(context, deviceId, values)
                }
            }.onFailure { failure ->
                Log.e(TAG, "Failed to sync projects to client", failure)
            }
        }
    }

    /**
     * Keeps the latest sample per sensor and sends all pending sensors as one small batch. This
     * avoids one fast sensor starving the others or creating a new HTTP request for every event.
     */
    fun syncSensorValuesToClient(context: Context, deviceId: String, values: List<Float>) {
        val applicationContext = context.applicationContext
        synchronized(sensorUploadLock) {
            pendingSensorValues[deviceId] = values.toList()
            if (sensorUploadJob?.isActive != true) {
                sensorUploadJob = clientScope.launch {
                    drainPendingSensorValues(applicationContext)
                }
            }
        }
    }

    private suspend fun drainPendingSensorValues(context: Context) {
        while (true) {
            delay(SENSOR_BATCH_INTERVAL_MS)
            val batch = synchronized(sensorUploadLock) {
                if (pendingSensorValues.isEmpty()) {
                    sensorUploadJob = null
                    return
                }
                pendingSensorValues.toMap().also { pendingSensorValues.clear() }
            }

            val result = ClientEndpointResolver.withFallback(context) { endpoint ->
                client.post("http://${endpoint.address}:8080/sensor-values") {
                    contentType(ContentType.Application.Json)
                    setBody(SensorValuesPayload(batch))
                }
            }
            result.onSuccess {
                Log.v(SENSOR_TAG, "Host uploaded ${batch.size} sensor value(s)")
            }.onFailure { failure ->
                synchronized(sensorUploadLock) {
                    batch.forEach { (deviceId, values) ->
                        pendingSensorValues.putIfAbsent(deviceId, values)
                    }
                }
                Log.w(SENSOR_TAG, "Sensor upload failed; latest values queued for retry", failure)
                delay(SENSOR_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun handleSwitchRequest(
        call: io.ktor.server.application.ApplicationCall,
        context: Context,
        clientOnly: Boolean
    ) {
        val request = try {
            call.receive<ToggleRequest>()
        } catch (failure: Exception) {
            Log.w(TAG, "Invalid switch request", failure)
            call.respondText("Invalid switch request", status = HttpStatusCode.BadRequest)
            return
        }

        val deviceMode = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("deviceMode", "default")
        if (clientOnly && deviceMode != "client") {
            call.respondText("Switch proxy is only available on a Client device", status = HttpStatusCode.Conflict)
            return
        }

        try {
            if (deviceMode == "client") {
                val module = ProjectManager.projectList.asSequence()
                    .flatMap { it.moduleList.asSequence() }
                    .firstOrNull { it.id == request.moduleId }
                if (module == null) {
                    call.respondText("Module not found", status = HttpStatusCode.NotFound)
                    return
                }

                managedEndpointFailure(module.deviceList)?.let { (_, state) ->
                    call.respondText(state.explanation, status = HttpStatusCode.ServiceUnavailable)
                    return
                }

                val targetCount = sendSwitchCommand(module.deviceList, request.value)
                if (targetCount == 0) {
                    call.respondText("No ESP8266 endpoint configured", status = HttpStatusCode.ServiceUnavailable)
                    return
                }
                module.value = if (request.value) 1.0 else 0.0
                call.respondText("Success")
                return
            }

            val database = AppDatabase.getDatabase(context)
            val module = database.moduleDao().getModuleById(request.moduleId)
            if (module == null) {
                call.respondText("Module not found", status = HttpStatusCode.NotFound)
                return
            }
            val peripherals = database.peripheralDao().getDevicesForModule(module.id)
            val targetCount = sendSwitchCommand(peripherals, request.value)
            if (targetCount == 0) {
                call.respondText("No ESP8266 endpoint configured", status = HttpStatusCode.ServiceUnavailable)
                return
            }
            module.value = if (request.value) 1.0 else 0.0
            database.moduleDao().updateModule(module)
            syncProjectsToClient(context)
            call.respondText("Success")
        } catch (failure: Exception) {
            Log.e(TAG, "Switch relay failed", failure)
            call.respondText("Switch relay failed", status = HttpStatusCode.BadGateway)
        }
    }

    private suspend fun sendSwitchCommand(devices: List<DeviceEntity>, enabled: Boolean): Int {
        val managersById = ManagerDiscovery.snapshot().associateBy { it.managerId }
        val targets = devices.mapNotNull { device -> resolveSwitchTarget(device, managersById) }
        targets.forEach { targetUrl ->
            Log.d(TAG, "Sending switch command to $targetUrl")
            client.post(targetUrl) {
                contentType(ContentType.Application.Json)
                setBody(mapOf("state" to if (enabled) "on" else "off"))
            }
        }
        return targets.size
    }

    private fun resolveSwitchTarget(
        device: DeviceEntity,
        managersById: Map<String, AdvertisedManager>
    ): String? {
        val manager = managersById[device.managerID]
        val peripheralId = device.effectiveManagerPeripheralId()
        val advertised = manager?.devices?.firstOrNull { it.id == peripheralId }
        if (manager != null && advertised != null) {
            val path = advertised.endpointPath.trim().ifBlank { "/relay" }
            if (path.startsWith("http://") || path.startsWith("https://")) return path
            return "http://${manager.endpoint}/${path.trimStart('/')}"
        }

        val address = device.ipAddress.trim().takeIf { it.isNotEmpty() } ?: return null
        return if (address.startsWith("http://") || address.startsWith("https://")) {
            address
        } else {
            "http://$address/relay"
        }
    }

    private fun managedEndpointFailure(
        devices: List<DeviceEntity>
    ): Pair<DeviceEntity, ManagedEndpointState>? {
        val managedDevices = devices.filter { it.managerID.isNotBlank() }
        if (managedDevices.isEmpty()) return null
        val managersById = ManagerDiscovery.snapshot().associateBy { it.managerId }
        return managedDevices.firstNotNullOfOrNull { device ->
            val state = device.managedEndpointState(managersById)
            if (state == ManagedEndpointState.AVAILABLE) null else device to state
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondDashboardIndex(context: Context) {
        respondDashboardAsset(context, "dashboard/index.html", ContentType.Text.Html)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondDashboardAsset(
        context: Context,
        path: String,
        explicitContentType: ContentType? = null
    ) {
        try {
            val inputStream: InputStream = context.assets.open(path)
            val bytes = inputStream.use { it.readBytes() }
            respondBytes(bytes, explicitContentType ?: contentTypeFor(path))
        } catch (e: IOException) {
            Log.e("test", "Dashboard asset not found: $path", e)
            respondText("Not found", status = HttpStatusCode.NotFound)
        }
    }

    private fun contentTypeFor(path: String): ContentType {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> ContentType.Text.Html
            "css" -> ContentType.Text.CSS
            "js", "mjs" -> ContentType.Application.JavaScript
            "json" -> ContentType.Application.Json
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "webp" -> ContentType("image", "webp")
            "woff" -> ContentType("font", "woff")
            "woff2" -> ContentType("font", "woff2")
            else -> ContentType.Application.OctetStream
        }
    }

}
