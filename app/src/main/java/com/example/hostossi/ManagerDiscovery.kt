package com.example.hostossi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AdvertisedPeripheral(
    val id: String,
    val kind: String,
    val type: String,
    val name: String,
    val unit: String = "",
    val endpointPath: String = ""
) {
    val isSwitch: Boolean
        get() = kind.equals("switch", ignoreCase = true) ||
            type.contains("switch", ignoreCase = true) ||
            type.contains("relay", ignoreCase = true)
}

@Serializable
data class AdvertisedManager(
    val managerId: String,
    val title: String,
    val ipAddress: String,
    val httpPort: Int = 80,
    val devices: List<AdvertisedPeripheral> = emptyList(),
    val lastSeenAt: Long
) {
    val endpoint: String
        get() = if (httpPort == 80) ipAddress else "$ipAddress:$httpPort"
}

enum class ManagedEndpointState(val label: String, val explanation: String) {
    AVAILABLE("ONLINE", "Manager and channel are available"),
    MANAGER_OFFLINE("MANAGER OFFLINE", "The manager is no longer advertising"),
    CHANNEL_REMOVED("CHANNEL REMOVED", "The assigned channel is no longer advertised by this manager"),
    TYPE_CHANGED("TYPE CHANGED", "The assigned channel changed between sensor and switch"),
    UNKNOWN("STATUS UNKNOWN", "The Client could not provide manager status")
}

/** Reads the legacy channel ID that older builds stored at the end of the description. */
fun DeviceEntity.effectiveManagerPeripheralId(): String {
    if (managerPeripheralId.isNotBlank()) return managerPeripheralId
    val candidate = description.substringAfterLast('(', "")
        .substringBeforeLast(')', "")
        .trim()
    return candidate.takeIf { description.trimEnd().endsWith("($it)") }.orEmpty()
}

fun DeviceEntity.managedEndpointState(
    managersById: Map<String, AdvertisedManager>,
    statusKnown: Boolean = true
): ManagedEndpointState {
    if (managerID.isBlank()) return ManagedEndpointState.AVAILABLE
    if (!statusKnown) return ManagedEndpointState.UNKNOWN
    val manager = managersById[managerID] ?: return ManagedEndpointState.MANAGER_OFFLINE
    val peripheralId = effectiveManagerPeripheralId()
    if (peripheralId.isBlank()) return ManagedEndpointState.AVAILABLE
    val advertised = manager.devices.firstOrNull { it.id == peripheralId }
        ?: return ManagedEndpointState.CHANNEL_REMOVED
    val expectedSwitch = type == DeviceType.SWITCH
    if (advertised.isSwitch != expectedSwitch) return ManagedEndpointState.TYPE_CHANGED
    if (!expectedSwitch) {
        val advertisedType = DeviceType.fromString(advertised.type)
        if (type != DeviceType.UNKNOWN && advertisedType != DeviceType.UNKNOWN && type != advertisedType) {
            return ManagedEndpointState.TYPE_CHANGED
        }
    }
    return ManagedEndpointState.AVAILABLE
}

@Serializable
private data class ManagerAdvertisement(
    val protocol: String,
    val version: Int,
    val managerId: String,
    val name: String = "ESP8266",
    val httpPort: Int = 80,
    val devices: List<AdvertisedPeripheral> = emptyList()
)

/** Receives the periodic UDP advertisements sent by OSSI ESP8266 managers. */
object ManagerDiscovery {
    const val PORT = 8266
    const val PROTOCOL = "ossi-manager"
    const val PROTOCOL_VERSION = 1
    private const val TAG = "ManagerDiscovery"
    private const val MAX_PACKET_SIZE = 4_096
    private const val STALE_AFTER_MS = 12_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val managers = ConcurrentHashMap<String, AdvertisedManager>()
    private val json = Json { ignoreUnknownKeys = true }
    private var listenJob: Job? = null
    @Volatile private var socket: DatagramSocket? = null

    @Synchronized
    fun start() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            try {
                DatagramSocket(null).use { receiver ->
                    receiver.reuseAddress = true
                    receiver.broadcast = true
                    receiver.soTimeout = 1_000
                    receiver.bind(InetSocketAddress(PORT))
                    socket = receiver
                    Log.i(TAG, "Listening for ESP8266 managers on UDP $PORT")
                    while (isActive) {
                        try {
                            receive(receiver)
                        } catch (_: SocketTimeoutException) {
                            reconcileProjectInventory()
                        }
                    }
                }
            } catch (failure: SocketException) {
                if (isActive) Log.e(TAG, "Manager listener failed", failure)
            } catch (failure: Exception) {
                Log.e(TAG, "Invalid manager advertisement", failure)
            } finally {
                socket = null
            }
        }
    }

    @Synchronized
    fun stop() {
        socket?.close()
        listenJob?.cancel()
        listenJob = null
        managers.clear()
    }

    fun snapshot(now: Long = System.currentTimeMillis()): List<AdvertisedManager> {
        val removed = managers.entries.removeIf { now - it.value.lastSeenAt > STALE_AFTER_MS }
        if (removed) Log.d(TAG, "Removed stale ESP8266 manager advertisements")
        val visible = managers.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        Log.v(TAG, "Discovery snapshot contains ${visible.size} manager(s)")
        return visible
    }

    private fun receive(receiver: DatagramSocket) {
        val bytes = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)
        receiver.receive(packet)
        val sourceAddress = packet.address.hostAddress.orEmpty()
        Log.v(TAG, "UDP packet from $sourceAddress (${packet.length} bytes)")
        runCatching {
            val advertisement = json.decodeFromString<ManagerAdvertisement>(
                packet.data.decodeToString(packet.offset, packet.offset + packet.length)
            )
            if (advertisement.protocol != PROTOCOL) {
                Log.w(TAG, "Ignored $sourceAddress: protocol '${advertisement.protocol}' is not '$PROTOCOL'")
                return
            }
            if (advertisement.version != PROTOCOL_VERSION) {
                Log.w(TAG, "Ignored $sourceAddress: unsupported protocol version ${advertisement.version}")
                return
            }
            val managerId = advertisement.managerId.trim()
            if (managerId.isBlank()) {
                Log.w(TAG, "Ignored $sourceAddress: managerId is empty")
                return
            }
            val title = advertisement.name.trim().takeIf { it.isNotBlank() } ?: "ESP8266"
            val httpPort = advertisement.httpPort.takeIf { it in 1..65535 } ?: 80
            managers[managerId] = AdvertisedManager(
                managerId = managerId,
                title = title,
                ipAddress = packet.address.hostAddress.orEmpty(),
                httpPort = httpPort,
                devices = advertisement.devices
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .distinctBy { it.id },
                lastSeenAt = System.currentTimeMillis()
            )
            Log.d(
                TAG,
                "Registered manager '$title' ($managerId) at $sourceAddress:$httpPort " +
                    "with ${advertisement.devices.size} device(s)"
            )
            reconcileProjectInventory()
        }.onFailure { Log.w(TAG, "Ignored malformed advertisement from $sourceAddress", it) }
    }

    private fun reconcileProjectInventory() {
        val invalidated = ProjectManager.reconcileManagerInventory(snapshot())
        if (invalidated > 0) {
            Log.i(TAG, "Invalidated $invalidated stale reading(s) after manager inventory changed")
        }
    }
}
