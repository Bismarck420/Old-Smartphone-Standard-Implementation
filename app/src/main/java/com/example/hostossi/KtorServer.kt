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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import java.io.InputStream

@Serializable
data class ProjectsResponse(val projects: List<Project>)

@Serializable
data class ToggleRequest(val projectId: String, val moduleId: String, val value: Boolean)

@Serializable
data class SensorValuesPayload(val values: Map<String, List<Float>>)

object KtorServer {
    private const val TAG = "KtorServer"

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
    private val activeClientSensors = mutableMapOf<String, MeasureableSensor>()
    @Volatile private var lastProjectSyncAt: Long = 0L

    fun isRunning(): Boolean = serverJob?.isActive == true
    fun activeClientSensorCount(): Int = activeClientSensors.size
    fun lastProjectSyncTimestamp(): Long = lastProjectSyncAt

    /** Executes a room-control action directly from the native client dashboard. */
    suspend fun controlClientSwitch(moduleId: String, enabled: Boolean): Result<Int> = runCatching {
        val module = ProjectManager.projectList.asSequence()
            .flatMap { it.moduleList.asSequence() }
            .firstOrNull { it.id == moduleId }
            ?: error("Switch module is no longer available")

        val targetCount = sendSwitchCommand(module.deviceList, enabled)
        if (targetCount == 0) error("No ESP8266 endpoint configured")
        module.value = if (enabled) 1.0 else 0.0
        targetCount
    }

    fun startServer(context: Context) {
        if (serverJob != null) return
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
                                Log.d(TAG, "Received ${response.projects.size} projects via /syncProjects")
                                ProjectManager.projectList.clear()
                                ProjectManager.projectList.addAll(response.projects)
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
                                payload.values.forEach { (deviceId, values) ->
                                    ProjectManager.updateSensorValues(deviceId, values)
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
                                call.respond(deviceSensors.map { sensor ->
                                    AndroidSensorDescriptor(
                                        name = sensor.name,
                                        sensorType = sensor.type,
                                        type = DeviceType.fromAndroidSensorType(sensor.type)
                                    )
                                })
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to get sensors", e)
                                call.respondText("Error fetching sensors", status = HttpStatusCode.InternalServerError)
                            }
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
                                    call.respond(ProjectsResponse(ProjectManager.projectList.toList()))
                                } else {
                                    // Host serves the website: we MUST provide the modules and devices
                                    // We use the in-memory list as it's the most up-to-date for live sensors
                                    call.respond(ProjectsResponse(ProjectManager.projectList.toList()))
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

        val devices = ProjectManager.projectList
            .asSequence()
            .flatMap { it.moduleList.asSequence() }
            .flatMap { it.deviceList.asSequence() }
            .filter { it.connectionType == ConnectionType.ANDROID }
            .associateBy { it.id }

        mainHandler.post {
            activeClientSensors.keys.toList()
                .filter { it !in devices }
                .forEach { deviceId -> activeClientSensors.remove(deviceId)?.stopListening() }

            devices.forEach { (deviceId, device) ->
                if (deviceId in activeClientSensors) return@forEach
                DeviceFactory.create(context, device)?.let { sensor ->
                    sensor.setOnSensorValuesChangedListener { values ->
                        ProjectManager.updateSensorValues(deviceId, values)
                    }
                    sensor.startListening()
                    activeClientSensors[deviceId] = sensor
                    Log.d(TAG, "Started client sensor ${device.name} ($deviceId)")
                }
            }
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

    fun syncProjectsToClient(context: Context) {
        clientScope.launch {
            val activeProjects = ProjectManager.projectList.toList()
            val result = ClientEndpointResolver.withFallback(context) { endpoint ->
                Log.d(TAG, "Syncing projects through ${endpoint.transport}: ${endpoint.address}")
                client.post("http://${endpoint.address}:8080/syncProjects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectsResponse(activeProjects))
                }
            }
            result.exceptionOrNull()?.let { Log.e(TAG, "Failed to sync projects to client", it) }
        }
    }

    /** Sends only changing sensor samples; project structure is deliberately not touched. */
    fun syncSensorValuesToClient(context: Context, deviceId: String, values: List<Float>) {
        clientScope.launch {
            val result = ClientEndpointResolver.withFallback(context) { endpoint ->
                client.post("http://${endpoint.address}:8080/sensor-values") {
                    contentType(ContentType.Application.Json)
                    setBody(SensorValuesPayload(mapOf(deviceId to values)))
                }
            }
            result.exceptionOrNull()?.let { Log.w(TAG, "Failed to sync sensor values", it) }
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
        val targets = devices.mapNotNull { device ->
            device.ipAddress.trim().takeIf { it.isNotEmpty() }
        }
        targets.forEach { address ->
            val targetUrl = if (address.startsWith("http://") || address.startsWith("https://")) {
                address
            } else {
                "http://$address/relay"
            }
            Log.d(TAG, "Sending switch command to $targetUrl")
            client.post(targetUrl) {
                contentType(ContentType.Application.Json)
                setBody(mapOf("state" to if (enabled) "on" else "off"))
            }
        }
        return targets.size
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
