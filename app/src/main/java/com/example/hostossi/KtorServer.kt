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
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
    }

    private var serverJob: Job? = null
    private var clientJob: Job? = null

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sensorData = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeClientSensors = mutableMapOf<String, MeasureableSensor>()

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

                        post("/toggleSwitch") {
                            try {
                                val bodyText = call.receiveText()
                                Log.d(TAG, "Incoming Toggle JSON: $bodyText")
                                val req = try {
                                    Gson().fromJson(bodyText, ToggleRequest::class.java)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse toggle request", e)
                                    null
                                }
                                
                                if (req == null) {
                                    call.respondText("Invalid JSON", status = HttpStatusCode.BadRequest)
                                    return@post
                                }

                                Log.d(TAG, "Toggle parsed: ${req.moduleId} -> ${req.value}")
                                
                                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                                val deviceMode = sharedPreferences.getString("deviceMode", "default")
                                
                                if (deviceMode == "client") {
                                    val hostIp = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
                                    if (hostIp.isNotBlank()) {
                                        Log.d(TAG, "Forwarding toggle to host: $hostIp")
                                        try {
                                            client.post("http://$hostIp:8080/toggleSwitch") {
                                                contentType(ContentType.Application.Json)
                                                setBody(req)
                                            }
                                            call.respondText("Forwarded")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to forward toggle to host", e)
                                            call.respondText("Host unreachable", status = HttpStatusCode.GatewayTimeout)
                                        }
                                    } else {
                                        Log.e(TAG, "No host IP configured on client")
                                        call.respondText("Host IP not set", status = HttpStatusCode.ServiceUnavailable)
                                    }
                                } else {
                                    val db = AppDatabase.getDatabase(context)
                                    val module = db.moduleDao().getModuleById(req.moduleId)
                                    if (module != null) {
                                        module.value = if (req.value) 1.0 else 0.0
                                        db.moduleDao().updateModule(module)
                                        Log.d(TAG, "Database updated for module ${module.id}")
                                        
                                        // ESP8266 Logic
                                        val peripherals = db.peripheralDao().getDevicesForModule(module.id)
                                        peripherals.forEach { p ->
                                            if (p.ipAddress.isNotBlank()) {
                                                clientScope.launch {
                                                    try {
                                                        Log.d(TAG, "Sending IOT command to ${p.ipAddress}")
                                                        client.post("http://${p.ipAddress}/relay") {
                                                            contentType(ContentType.Application.Json)
                                                            setBody(mapOf("state" to if (req.value) "on" else "off"))
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "IOT Target unreachable: ${p.ipAddress}", e)
                                                    }
                                                }
                                            }
                                        }
                                        syncProjectsToClient(context)
                                        call.respondText("Success")
                                    } else {
                                        Log.e(TAG, "Module ${req.moduleId} not found in DB")
                                        call.respondText("Module not found", status = HttpStatusCode.NotFound)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Critical toggle error", e)
                                call.respondText("Internal Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                            }
                        }

                        get("/discovery") {
                            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                            val deviceMode = sharedPreferences.getString("deviceMode", "default")
                            
                            if (deviceMode == "client") {
                                call.respondText("hostossi-client")
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
            try {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val clientIpAddress = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
                if (clientIpAddress.isBlank()) {
                    Log.w(TAG, "No client IP configured, cannot send selected project")
                    return@launch
                }

                client.post("http://$clientIpAddress:8080/selectedProject") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectManager.hostSelectedProject)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Fehler beim Senden selectedProject", ex)
            }
        }
    }

    fun syncProjectsToClient(context: Context) {
        clientScope.launch {
            try {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val clientIpAddress = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
                if (clientIpAddress.isBlank()) {
                    Log.w(TAG, "No client IP configured, cannot sync projects")
                    return@launch
                }

                // Send the active in-memory list for live updates
                val activeProjects = ProjectManager.projectList.toList()
                
                client.post("http://$clientIpAddress:8080/syncProjects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectsResponse(activeProjects))
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to sync projects to client", ex)
            }
        }
    }

    /** Sends only changing sensor samples; project structure is deliberately not touched. */
    fun syncSensorValuesToClient(context: Context, deviceId: String, values: List<Float>) {
        clientScope.launch {
            try {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val clientIpAddress = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
                if (clientIpAddress.isBlank()) return@launch
                client.post("http://$clientIpAddress:8080/sensor-values") {
                    contentType(ContentType.Application.Json)
                    setBody(SensorValuesPayload(mapOf(deviceId to values)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync sensor values", e)
            }
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
