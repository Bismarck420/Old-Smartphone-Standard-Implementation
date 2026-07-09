package com.example.hostossi

import android.content.ContentValues
import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import io.ktor.server.application.hooks.CallSetup.install
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.websocket.WebSocketDeflateExtension.Companion.install
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext.get


class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener
    { // Schnittstelle hinzufügen

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
            val name = sharedPreferences.getString("deviceMode", "")

            if(name == "client"){

                val hostNamePref = findPreference<EditTextPreference>("client_IP")
                hostNamePref?.isEnabled = false
                findPreference<Preference>("scan_client")?.isEnabled = false

            }
            else{
                val hostNamePref = findPreference<EditTextPreference>("client_IP")
                hostNamePref?.isEnabled = true
                findPreference<Preference>("scan_client")?.isEnabled = true
            }

            findPreference<Preference>("scan_client")?.setOnPreferenceClickListener {
                scanForClient()
                true
            }


        }

        override fun onResume() {
            super.onResume()
            // Hier sagen wir: "Bitte informiere mich bei Änderungen"
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            // Wichtig: Wieder abmelden, um Speicherlecks zu vermeiden
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            if (key == "deviceMode") {
                val newValue = sharedPreferences.getString(key, "Default")
                val navView : BottomNavigationView = requireActivity().findViewById(R.id.bottomNavigationView)

                if(newValue == "client"){
                    ProjectManager.hostSelectedProject = Project()

                    navView.menu.findItem(R.id.clientDashboard).isVisible = true
                    navView.menu.findItem(R.id.projects).isVisible = false
                    val hostNamePref = findPreference<EditTextPreference>("client_IP")
                    hostNamePref?.isEnabled = false
                    findPreference<Preference>("scan_client")?.isEnabled = false

                    getOnBoardSensors()

                    KtorServer.startServer(requireActivity())

                    //TODO add web server capabilities
                }
                else if (newValue == "host"){
                    navView.menu.findItem(R.id.clientDashboard).isVisible = false
                    navView.menu.findItem(R.id.projects).isVisible = true
                    val hostNamePref = findPreference<EditTextPreference>("client_IP")
                    hostNamePref?.isEnabled = true
                    findPreference<Preference>("scan_client")?.isEnabled = true

                    KtorServer.stopServer()
                }
            }
        }

        private fun scanForClient() {
            val scanPreference = findPreference<Preference>("scan_client")
            scanPreference?.summary = "Scanning local network..."
            scanPreference?.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val clientIpAddress = NetworkDiscovery.findClient()
                scanPreference?.isEnabled = true

                if (clientIpAddress == null) {
                    scanPreference?.summary = "No client found"
                    Toast.makeText(requireContext(), "No hostOSSI client found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                preferenceManager.sharedPreferences
                    ?.edit()
                    ?.putString("client_IP", clientIpAddress)
                    ?.apply()

                findPreference<EditTextPreference>("client_IP")?.text = clientIpAddress
                scanPreference?.summary = "Found client at $clientIpAddress"
                Toast.makeText(requireContext(), "Client found: $clientIpAddress", Toast.LENGTH_SHORT).show()
            }
        }

        fun getOnBoardSensors(){
            val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val deviceSensors: List<Sensor> = sensorManager.getSensorList(Sensor.TYPE_ALL)

        }
    }
