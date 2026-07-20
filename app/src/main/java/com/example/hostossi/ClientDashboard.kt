package com.example.hostossi

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hostossi.databinding.FragmentClientDashboardBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ClientDashboard : Fragment() {
    private var _binding: FragmentClientDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_client_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentClientDashboardBinding.bind(view)
        renderStatus()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    renderStatus()
                    delay(1_000)
                }
            }
        }
    }

    private fun renderStatus() {
        if (_binding == null) return
        val ipAddress = NetworkDiscovery.localIpv4Address()
        val projects = ProjectManager.projectList.toList()
        val modules = projects.flatMap { it.moduleList }
        val devices = modules.flatMap { it.deviceList }
        val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val serverOnline = KtorServer.isRunning()

        binding.deviceName.text = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replaceFirstChar { it.uppercase() }
        binding.serverStatus.text = if (serverOnline) "Online" else "Stopped"
        binding.serverStatus.setTextColor(
            ContextCompat.getColor(requireContext(), if (serverOnline) R.color.online_front_color else android.R.color.holo_red_light)
        )
        binding.serverStatusCard.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), if (serverOnline) R.color.online_back_color else android.R.color.holo_red_dark)
        )
        binding.serverUrl.text = ipAddress?.let { "http://$it:8080" } ?: "Address unavailable"
        binding.ipAddress.text = if (ipAddress == null) {
            "Connect this device to a local network"
        } else {
            "Reachable by browsers on the same local network"
        }

        binding.projectsStat.projectCount.text = projects.size.toString()
        binding.modulesStat.moduleCount.text = modules.size.toString()
        binding.sensorsStat.sensorCount.text = sensorManager.getSensorList(Sensor.TYPE_ALL).size.toString()
        binding.streamsStat.streamCount.text = KtorServer.activeClientSensorCount().toString()

        binding.syncSummary.text = when {
            projects.isEmpty() -> "Waiting for the host to synchronize a project."
            else -> "${projects.joinToString(limit = 3) { it.name }} · ${devices.size} configured device${if (devices.size == 1) "" else "s"}"
        }
        val lastSync = KtorServer.lastProjectSyncTimestamp()
        binding.lastSync.text = if (lastSync == 0L) {
            "No project sync received yet"
        } else {
            "Updated ${DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)}"
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
