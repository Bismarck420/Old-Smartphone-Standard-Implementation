package com.example.hostossi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import java.net.Inet4Address
import java.net.NetworkInterface

data class TailscaleStatus(
    val installed: Boolean,
    val ipv4Address: String?
) {
    val connected: Boolean get() = ipv4Address != null
}

object TailscaleIntegration {
    const val PACKAGE_NAME = "com.tailscale.ipn"
    private const val CACHE_DURATION_MS = 2_000L

    @Volatile private var cachedStatus: TailscaleStatus? = null
    @Volatile private var cachedAt: Long = 0L

    fun status(context: Context, forceRefresh: Boolean = false): TailscaleStatus {
        val now = System.currentTimeMillis()
        cachedStatus?.takeIf { !forceRefresh && now - cachedAt < CACHE_DURATION_MS }?.let { return it }

        val applicationContext = context.applicationContext
        val installed = isInstalled(applicationContext)
        val status = TailscaleStatus(
            installed = installed,
            ipv4Address = if (installed) activeTailscaleIpv4(applicationContext) else null
        )
        cachedStatus = status
        cachedAt = now
        return status
    }

    fun isTailscaleIpv4(address: String?): Boolean {
        val octets = address?.trim()?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        return octets.size == 4 && octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
    }

    fun openOrInstall(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (launchIntent != null) {
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE_NAME"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE_NAME"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(marketIntent) }
            .recoverCatching { context.startActivity(webIntent) }
    }

    private fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun activeTailscaleIpv4(context: Context): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val vpnAddress = activeNetwork
            ?.takeIf { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            ?.let { network -> connectivityManager.getLinkProperties(network)?.linkAddresses.orEmpty() }
            .orEmpty()
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull(::isTailscaleIpv4)
        if (vpnAddress != null) return vpnAddress

        // Some Android VPN implementations expose the tunnel only as a network interface.
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .filter { networkInterface ->
                    val name = networkInterface.name.lowercase()
                    name.startsWith("tun") || name.contains("tailscale")
                }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull(::isTailscaleIpv4)
        }.getOrNull()
    }
}

enum class ClientTransport { TAILSCALE, LOCAL_NETWORK, MANUAL }

data class ResolvedClientEndpoint(
    val address: String,
    val transport: ClientTransport
)

object ClientEndpointResolver {
    const val KEY_CLIENT_ADDRESS = "client_IP"
    const val KEY_CLIENT_LAN_ADDRESS = "client_lan_IP"
    const val KEY_CLIENT_TAILSCALE_ADDRESS = "client_tailscale_IP"
    const val KEY_PREFER_TAILSCALE = "prefer_tailscale"

    @Volatile private var lastSuccessfulAddress: String? = null

    fun resolve(context: Context): ResolvedClientEndpoint? = candidates(context).firstOrNull()

    fun candidates(context: Context): List<ResolvedClientEndpoint> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val configured = preferences.getString(KEY_CLIENT_ADDRESS, "")?.trim().orEmpty()
        val lanAddress = preferences.getString(KEY_CLIENT_LAN_ADDRESS, "")?.trim().orEmpty()
        val tailscaleAddress = preferences.getString(KEY_CLIENT_TAILSCALE_ADDRESS, "")?.trim().orEmpty()
        val preferTailscale = preferences.getBoolean(KEY_PREFER_TAILSCALE, true)
        val tailscaleConnected = TailscaleIntegration.status(context).connected

        val tailscale = listOf(tailscaleAddress, configured)
            .firstOrNull(TailscaleIntegration::isTailscaleIpv4)
            ?.let { ResolvedClientEndpoint(it, ClientTransport.TAILSCALE) }
        val local = lanAddress.takeIf { it.isNotBlank() }
            ?.let { ResolvedClientEndpoint(it, ClientTransport.LOCAL_NETWORK) }
        val manual = configured.takeIf { it.isNotBlank() }
            ?.let { address ->
                ResolvedClientEndpoint(
                    address,
                    if (TailscaleIntegration.isTailscaleIpv4(address)) ClientTransport.TAILSCALE else ClientTransport.MANUAL
                )
            }

        val ordered = if (preferTailscale && tailscaleConnected) {
            listOfNotNull(tailscale, local, manual)
        } else {
            listOfNotNull(local, manual, tailscale)
        }
        return ordered.distinctBy { it.address }
    }

    suspend fun <T> withFallback(
        context: Context,
        operation: suspend (ResolvedClientEndpoint) -> T
    ): Result<T> {
        var lastFailure: Throwable = IllegalStateException("No Client address configured")
        val availableEndpoints = candidates(context)
        val successfulEndpoint = lastSuccessfulAddress?.let { address ->
            availableEndpoints.firstOrNull { it.address == address }
        }
        val orderedEndpoints = listOfNotNull(successfulEndpoint) +
            availableEndpoints.filterNot { it.address == successfulEndpoint?.address }

        for (endpoint in orderedEndpoints) {
            try {
                val result = operation(endpoint)
                lastSuccessfulAddress = endpoint.address
                return Result.success(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        return Result.failure(lastFailure)
    }

    fun hasConfiguredClient(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return listOf(KEY_CLIENT_ADDRESS, KEY_CLIENT_LAN_ADDRESS, KEY_CLIENT_TAILSCALE_ADDRESS)
            .any { preferences.getString(it, "")?.isNotBlank() == true }
    }

    fun rememberDiscovery(context: Context, client: DiscoveredClient) {
        val preferred = client.tailscaleAddress
            ?.takeIf {
                TailscaleIntegration.status(context).connected &&
                    TailscaleIntegration.isTailscaleIpv4(it)
            }
            ?: client.lanAddress

        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_CLIENT_ADDRESS, preferred)
            .putString(KEY_CLIENT_LAN_ADDRESS, client.lanAddress)
            .putString(KEY_CLIENT_TAILSCALE_ADDRESS, client.tailscaleAddress.orEmpty())
            .apply()
    }

    fun rememberManualAddress(context: Context, address: String) {
        val value = address.trim()
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_CLIENT_ADDRESS, value)
        if (TailscaleIntegration.isTailscaleIpv4(value)) {
            editor.putString(KEY_CLIENT_TAILSCALE_ADDRESS, value)
                .remove(KEY_CLIENT_LAN_ADDRESS)
        } else {
            editor.putString(KEY_CLIENT_LAN_ADDRESS, value)
                .remove(KEY_CLIENT_TAILSCALE_ADDRESS)
        }
        editor.apply()
    }
}
