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

    public var isStarted = true
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 8080) {
        }
    private var client: HttpClient = HttpClient(CIO){
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
            try{
                server.application.install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }

                server.application.routing {
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
                        val css = context.assets.open("css/templatemo-crypto-dashboard.css").bufferedReader().use { it.readText() }
                        call.respondText(css, ContentType.Text.CSS)
                    }

                    post("/payload") {
                        val gson  = Gson()
                        val project = call.receiveText()
                        val projectObject = gson.fromJson(project, Project::class.java)
                        ProjectManager.selectedProject = projectObject
                        call.respondText { "payload received" }

                    }
                }

                server.start(wait = true)
            }catch(ex : Exception){
                Log.d("test", "failed to start server")
            }



        }
    }


    fun stopServer() {
        // Das Abbrechen des Jobs beendet die Coroutine und den Server
        serverJob?.cancel()
        serverJob = null
    }

    fun startClient(context: Context){
        if(clientJob != null) return
        clientJob = clientScope.launch {
            try{

                val gson : Gson = Gson()
                val gsonProject = gson.toJson(ProjectManager.selectedProject)
                val response: HttpResponse = client.post("http://100.113.232.96:8080/payload") {
                    setBody(gsonProject)
                }
                Log.d("test", response.bodyAsText())
            }catch(ex : Exception){
                Log.d("test", "failed to connect to server")
            }

        }

    }

    fun stopClient(){
        clientJob?.cancel()
        clientJob = null
    }
}




