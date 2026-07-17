package com.example.hostossi

import android.app.ProgressDialog
import android.content.Context
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
                                call.respondText("Projects synced")
                            } catch (e: Exception) {
                                Log.e(TAG, "Mapping /syncProjects failed", e)
                                call.respondText("Sync failed: ${e.message}", status = HttpStatusCode.BadRequest)
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
                                val sensorNames = deviceSensors.map { it.name }

                                val lightSensor : LightSensor = LightSensor(context)
                                lightSensor.startListening()
                                lightSensor.setOnSensorValuesChangedListener { values ->
                                    Log.d("sensorvalues", "Light Sensor Value: " + values[0].toString())
                                }

                                call.respond(sensorNames)
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
                                    val db = AppDatabase.getDatabase(context)
                                    val projectsWithModules = try {
                                        db.projectDao().getAllWithModulesOnce()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Database access failed in /projects.json", e)
                                        emptyList()
                                    }
                                    
                                    val fullProjects = projectsWithModules.map { pwm ->
                                        val p = pwm.project.copy()
                                        p.moduleList = pwm.modules.toMutableList()
                                        p
                                    }
                                    call.respond(ProjectsResponse(fullProjects))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in /projects.json", e)
                                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                            }
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
        serverJob?.cancel()
        serverJob = null
        Log.d(TAG, "Server stopped")
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

                val db = AppDatabase.getDatabase(context)
                val projectsWithModules = try {
                    db.projectDao().getAllWithModulesOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Database access failed during sync", e)
                    return@launch
                }
                
                val fullProjects = projectsWithModules.map { pwm ->
                    val p = pwm.project.copy()
                    p.moduleList = pwm.modules.toMutableList()
                    p
                }
                
                client.post("http://$clientIpAddress:8080/syncProjects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectsResponse(fullProjects))
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to sync projects to client", ex)
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
