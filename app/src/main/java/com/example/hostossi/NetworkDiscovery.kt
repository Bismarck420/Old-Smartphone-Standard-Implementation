package com.example.hostossi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

object NetworkDiscovery {
    private const val DISCOVERY_PORT = 8080
    private const val DISCOVERY_PATH = "/discovery"
    private const val DISCOVERY_SIGNATURE = "hostossi-client"
    private const val CONNECT_TIMEOUT_MS = 350
    private const val READ_TIMEOUT_MS = 350
    private const val PARALLEL_SCAN_SIZE = 32
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DiscoveryResponse(
        val signature: String = DISCOVERY_SIGNATURE,
        val tailscaleAddress: String? = null
    )

    fun localIpv4Address(): String? {
        return localIpv4Addresses().firstOrNull()
    }

    fun preferredLocalIpv4Address(context: android.content.Context): String? =
        TailscaleIntegration.status(context).ipv4Address ?: localIpv4Address()

    private fun localIpv4Addresses(): List<String> {
        val interfaces = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { networkInterface ->
                when {
                    networkInterface.name.startsWith("wlan") -> 0
                    networkInterface.name.startsWith("eth") -> 1
                    else -> 2
                }
            }

        return interfaces
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filterNot { it.startsWith("127.") }
            .distinct()
            .toList()
    }

    suspend fun findClient(): DiscoveredClient? = withContext(Dispatchers.IO) {
        val localIps = localIpv4Addresses().filterNot(TailscaleIntegration::isTailscaleIpv4)
        if (localIps.isEmpty()) return@withContext null

        for (localIp in localIps) {
            val subnetPrefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
            if (subnetPrefix.isBlank()) continue

            val candidates = (1..254)
                .map { "$subnetPrefix.$it" }
                .filterNot { it == localIp }

            for (chunk in candidates.chunked(PARALLEL_SCAN_SIZE)) {
                val found = coroutineScope {
                    chunk.map { candidate ->
                        async {
                            discoverClient(candidate)
                        }
                    }.awaitAll().firstOrNull { it != null }
                }

                if (found != null) return@withContext found
            }
        }

        null
    }

    private fun discoverClient(address: String): DiscoveredClient? {
        val connection = (URL("http://$address:$DISCOVERY_PORT$DISCOVERY_PATH").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (!body.contains(DISCOVERY_SIGNATURE)) return null
            val response = runCatching { json.decodeFromString<DiscoveryResponse>(body) }.getOrNull()
            DiscoveredClient(
                lanAddress = address,
                tailscaleAddress = response?.tailscaleAddress?.takeIf(TailscaleIntegration::isTailscaleIpv4)
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

data class DiscoveredClient(
    val lanAddress: String,
    val tailscaleAddress: String?
)
