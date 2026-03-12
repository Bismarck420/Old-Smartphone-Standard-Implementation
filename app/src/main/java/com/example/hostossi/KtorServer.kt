package com.example.hostossi

import android.app.ProgressDialog
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStarting
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.json.buildJsonArray
import java.io.InputStream
import io.ktor.server.plugins.contentnegotiation.*


object KtorServer {

    private var client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }

    }

    private var serverJob: Job? = null
    private var clientJob: Job? = null

    // Wir erstellen einen Scope, der im Hintergrund läuft (IO = Input/Output)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    fun startServer(context: Context) {
        if (serverJob != null) return
        val mDialog = ProgressDialog(context)
        serverJob = serverScope.launch {
            try {
                Log.d("test", "server started")
                val server = embeddedServer(Netty, port = 8080) {
                    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                        json() // Nutzt kotlinx.serialization
                    }

                    // 2. Routing definieren
                    routing {
                        post("/selectedProject") {
                            try {
                                // WICHTIG: Deine Klasse "Project" muss @Serializable sein!
                                val project = call.receive<Project>()
                                ProjectManager.clientSelectedProject = project
                                Log.d("test", ProjectManager.clientSelectedProject.toString())
                                Log.d("test", "Project set: ${project.name}")
                                Log.d("test", project.toString())
                                call.respondText("selected Project received")
                            } catch (e: Exception) {
                                Log.e("test", "Mapping failed", e)
                            }
                        }
                        get("/tasks") {
                            var htmlContent: String = ""
                            val myInputStream: InputStream

                            try {
                                myInputStream = context.assets.open("index.html")
                                val size: Int = myInputStream.available()
                                val buffer = ByteArray(size)
                                myInputStream.read(buffer)
                                htmlContent = String(buffer)

                                call.respondText(htmlContent, ContentType.Text.Html)
                            } catch (e: IOException) {
                                // Exception
                                e.printStackTrace()
                            }
                        }
                        get("/css/templatemo-crypto-dashboard.css") {
                            val css = context.assets.open("css/templatemo-crypto-dashboard.css")
                                .bufferedReader().use { it.readText() }
                            call.respondText(css, ContentType.Text.CSS)
                        }
                        post("/sensorData") {
                            val gson = Gson()
                            val sensorData = call.receiveText()
                            call.respondText { "sensor data received" }
                        }
                    }
                }.start(wait = true)
                Log.d("test", "server started")

            } catch (ex: Exception) {
                Log.d("test", "failed to start server")
            }
        }
    }


    fun stopServer() {
        // Das Abbrechen des Jobs beendet die Coroutine und den Server
        serverJob?.cancel()
        serverJob = null
    }

    fun sendSelectedProject(context: Context) {
        clientScope.launch {
            try {
                client.post("http://100.113.232.96:8080/selectedProject") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectManager.hostSelectedProject)
                    Log.d("test", ProjectManager.hostSelectedProject.toString())

                }
            } catch (ex: Exception) {
                Log.e(
                    "test",
                    "Fehler beim Senden",
                    ex
                ) // ex zeigt dir genau, was kaputt ist!
            }
        }
    }
}




