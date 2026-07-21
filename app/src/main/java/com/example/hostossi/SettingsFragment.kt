package com.example.hostossi

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        configureModePreferences()
        configureClientAddressPreference()

        findPreference<Preference>("scan_client")?.setOnPreferenceClickListener {
            scanForClient()
            true
        }
        findPreference<Preference>("tailscale_action")?.setOnPreferenceClickListener {
            TailscaleIntegration.openOrInstall(requireContext())
            true
        }
        refreshTailscalePreferences(forceRefresh = true)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        refreshTailscalePreferences(forceRefresh = true)
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            "deviceMode" -> {
                configureModePreferences()
                applyDeviceMode(sharedPreferences.getString(key, "host"))
            }
            "theme_mode" -> applyTheme(sharedPreferences.getString(key, "system"))
            ClientEndpointResolver.KEY_PREFER_TAILSCALE -> refreshTailscalePreferences()
        }
    }

    private fun configureModePreferences() {
        val isClient = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("deviceMode", "host") == "client"
        findPreference<EditTextPreference>(ClientEndpointResolver.KEY_CLIENT_ADDRESS)?.isEnabled = !isClient
        findPreference<Preference>("scan_client")?.isEnabled = !isClient
    }

    private fun configureClientAddressPreference() {
        findPreference<EditTextPreference>(ClientEndpointResolver.KEY_CLIENT_ADDRESS)
            ?.setOnPreferenceChangeListener { _, newValue ->
                ClientEndpointResolver.rememberManualAddress(requireContext(), newValue?.toString().orEmpty())
                true
            }
    }

    private fun applyDeviceMode(mode: String?) {
        val navigation: BottomNavigationView = requireActivity().findViewById(R.id.bottomNavigationView)
        if (mode == "client") {
            ProjectManager.hostSelectedProject = Project()
            navigation.menu.findItem(R.id.clientDashboard).isVisible = true
            navigation.menu.findItem(R.id.projects).isVisible = false
            KtorServer.stopServer()
            KtorServer.startServer(requireActivity())
        } else {
            navigation.menu.findItem(R.id.clientDashboard).isVisible = false
            navigation.menu.findItem(R.id.projects).isVisible = true
            KtorServer.stopServer()
        }
    }

    private fun applyTheme(themeValue: String?) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeValue) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun refreshTailscalePreferences(forceRefresh: Boolean = false) {
        if (!isAdded) return
        val status = TailscaleIntegration.status(requireContext(), forceRefresh)
        val endpoint = ClientEndpointResolver.resolve(requireContext())
        val statusPreference = findPreference<Preference>("tailscale_status")
        val actionPreference = findPreference<Preference>("tailscale_action")

        statusPreference?.summary = buildString {
            append(
                when {
                    !status.installed -> "Not installed"
                    status.connected -> "Connected · This device: ${status.ipv4Address}"
                    else -> "Installed · VPN connection inactive"
                }
            )
            endpoint?.let {
                append("\nClient route: ${it.address} via ${it.transport.displayName()}")
            }
        }
        actionPreference?.title = if (status.installed) "Open Tailscale" else "Install Tailscale"
        actionPreference?.summary = if (status.installed) {
            "Connect or review the VPN in the Tailscale app"
        } else {
            "Get the official Android app from Google Play"
        }
    }

    private fun ClientTransport.displayName(): String = when (this) {
        ClientTransport.TAILSCALE -> "Tailscale"
        ClientTransport.LOCAL_NETWORK -> "local network"
        ClientTransport.MANUAL -> "configured address"
    }

    private fun scanForClient() {
        val scanPreference = findPreference<Preference>("scan_client")
        scanPreference?.summary = "Scanning local network…"
        scanPreference?.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val client = NetworkDiscovery.findClient()
            scanPreference?.isEnabled = true
            val navigation: BottomNavigationView? = activity?.findViewById(R.id.bottomNavigationView)

            if (client == null) {
                scanPreference?.summary = "No Client found"
                SnackbarUtils.showModernSnackbar(requireView(), "No hostOSSI Client found", anchorView = navigation)
                return@launch
            }

            ClientEndpointResolver.rememberDiscovery(requireContext(), client)
            val endpoint = ClientEndpointResolver.resolve(requireContext())
            scanPreference?.summary = if (client.tailscaleAddress != null) {
                "Paired locally · Tailscale ${client.tailscaleAddress} saved"
            } else {
                "Found Client at ${client.lanAddress} · Tailscale unavailable"
            }
            refreshTailscalePreferences(forceRefresh = true)
            SnackbarUtils.showModernSnackbar(
                requireView(),
                "Client route: ${endpoint?.address ?: client.lanAddress}",
                anchorView = navigation
            )
        }
    }
}
