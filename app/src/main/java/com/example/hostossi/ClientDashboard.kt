package com.example.hostossi

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hostossi.databinding.FragmentClientDashboardBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class ClientDashboard : Fragment() {
    private var _binding: FragmentClientDashboardBinding? = null
    private val binding get() = _binding!!

    private var selectedProjectId: String? = null
    private var controlProjectList: RecyclerView? = null
    private var controlModuleGrid: RecyclerView? = null
    private var controlProjectTitle: TextView? = null
    private var controlEmptyState: TextView? = null
    private var controlProjectAdapter: ControlProjectAdapter? = null
    private var controlModuleAdapter: ControlModuleAdapter? = null
    private val pendingSwitches = mutableSetOf<String>()

    private val isControlPanelMode: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_client_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentClientDashboardBinding.bind(view)
        if (isControlPanelMode) setupControlPanel(view)
        renderDashboard()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    renderDashboard()
                    delay(if (isControlPanelMode) 500 else 1_000)
                }
            }
        }
    }

    private fun renderDashboard() {
        renderStatus()
        if (isControlPanelMode) renderControlPanel()
    }

    private fun renderStatus() {
        if (_binding == null) return
        val ipAddress = NetworkDiscovery.preferredLocalIpv4Address(requireContext())
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
            "No project sync yet"
        } else {
            "Updated ${DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)}"
        }
    }

    private fun setupControlPanel(view: View) {
        controlProjectList = view.findViewById(R.id.controlProjectList)
        controlModuleGrid = view.findViewById(R.id.controlModuleGrid)
        controlProjectTitle = view.findViewById(R.id.controlProjectTitle)
        controlEmptyState = view.findViewById(R.id.controlEmptyState)

        controlProjectAdapter = ControlProjectAdapter { project ->
            selectedProjectId = project.id
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            renderControlPanel()
        }
        controlModuleAdapter = ControlModuleAdapter(::toggleSwitch)

        controlProjectList?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = controlProjectAdapter
            setHasFixedSize(false)
        }
        controlModuleGrid?.apply {
            val columns = if (resources.configuration.screenWidthDp >= 900) 3 else 2
            layoutManager = GridLayoutManager(requireContext(), columns)
            adapter = controlModuleAdapter
            setHasFixedSize(false)
        }
    }

    private fun renderControlPanel() {
        val projects = ProjectManager.projectList.toList().sortedBy { it.displayOrder }
        val remoteSelection = ProjectManager.clientSelectedProject.id
            .takeIf { candidate -> projects.any { it.id == candidate } }
        if (selectedProjectId == null || projects.none { it.id == selectedProjectId }) {
            selectedProjectId = remoteSelection ?: projects.firstOrNull()?.id
        }

        controlProjectAdapter?.submit(projects, selectedProjectId)
        val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
        controlProjectTitle?.text = selectedProject?.name ?: "Room control"

        val modules = selectedProject?.moduleList?.sortedBy { it.displayOrder }.orEmpty()
        controlModuleAdapter?.submit(modules, pendingSwitches)
        controlEmptyState?.apply {
            isVisible = modules.isEmpty()
            text = if (projects.isEmpty()) {
                "Waiting for a project from the host"
            } else {
                "No controls have been added to this project"
            }
        }
        controlModuleGrid?.isVisible = modules.isNotEmpty()
    }

    private fun toggleSwitch(module: Module, enabled: Boolean) {
        if (!pendingSwitches.add(module.id)) return
        controlModuleAdapter?.submit(
            ProjectManager.findProject(selectedProjectId.orEmpty())?.moduleList.orEmpty(),
            pendingSwitches
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val result = KtorServer.controlClientSwitch(module.id, enabled)
            pendingSwitches.remove(module.id)
            if (result.isSuccess) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                SnackbarUtils.showModernSnackbar(
                    binding.root,
                    result.exceptionOrNull()?.message ?: "Could not reach the switch"
                )
            }
            renderControlPanel()
        }
    }

    private inner class ControlProjectAdapter(
        private val onProjectSelected: (Project) -> Unit
    ) : RecyclerView.Adapter<ControlProjectViewHolder>() {
        private var projects: List<Project> = emptyList()
        private var selectedId: String? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ControlProjectViewHolder {
            val button = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_control_project, parent, false) as MaterialButton
            return ControlProjectViewHolder(button)
        }

        override fun onBindViewHolder(holder: ControlProjectViewHolder, position: Int) {
            val project = projects[position]
            val selected = project.id == selectedId
            val selectedBackground = MaterialColors.getColor(holder.button, com.google.android.material.R.attr.colorSecondaryContainer)
            val selectedText = MaterialColors.getColor(holder.button, com.google.android.material.R.attr.colorOnSecondaryContainer)
            val normalText = MaterialColors.getColor(holder.button, com.google.android.material.R.attr.colorOnSurfaceVariant)
            holder.button.apply {
                text = project.name.ifBlank { "Untitled project" }
                contentDescription = "$text, ${project.moduleList.size} modules"
                backgroundTintList = ColorStateList.valueOf(if (selected) selectedBackground else Color.TRANSPARENT)
                setTextColor(if (selected) selectedText else normalText)
                setOnClickListener { onProjectSelected(project) }
            }
        }

        override fun getItemCount(): Int = projects.size

        fun submit(items: List<Project>, selectedProjectId: String?) {
            val changed = projects.map { it.id to it.name } != items.map { it.id to it.name } || selectedId != selectedProjectId
            projects = items
            selectedId = selectedProjectId
            if (changed) notifyDataSetChanged()
        }
    }

    private class ControlProjectViewHolder(val button: MaterialButton) : RecyclerView.ViewHolder(button)

    private inner class ControlModuleAdapter(
        private val onSwitchChanged: (Module, Boolean) -> Unit
    ) : RecyclerView.Adapter<ControlModuleViewHolder>() {
        private var modules: List<Module> = emptyList()
        private var pending: Set<String> = emptySet()
        private var managersById: Map<String, AdvertisedManager> = emptyMap()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ControlModuleViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_control_module, parent, false)
            return ControlModuleViewHolder(view)
        }

        override fun onBindViewHolder(holder: ControlModuleViewHolder, position: Int) {
            val module = modules[position]
            val isSwitch = module.moduleType.equals("Switch", ignoreCase = true)
            val managedDevice = module.deviceList.firstOrNull { it.managerID.isNotBlank() }
            val endpointState = managedDevice?.managedEndpointState(managersById)
                ?: ManagedEndpointState.AVAILABLE
            holder.type.text = if (isSwitch) "ROOM CONTROL" else "LIVE SENSORS"
            holder.title.text = module.moduleTitle.ifBlank { "Untitled module" }
            holder.description.text = buildString {
                append(module.description)
                if (endpointState != ManagedEndpointState.AVAILABLE) {
                    if (isNotEmpty()) append("\n")
                    append(endpointState.label).append(" · ").append(endpointState.explanation)
                }
            }
            holder.description.isVisible = holder.description.text.isNotBlank()
            holder.readings.removeAllViews()
            holder.readings.isVisible = !isSwitch
            holder.switchArea.isVisible = isSwitch

            if (isSwitch) {
                bindSwitch(holder, module, endpointState)
            } else {
                val devices = module.deviceList.filter { it.type != DeviceType.SWITCH }
                if (endpointState != ManagedEndpointState.AVAILABLE) {
                    holder.readings.addView(createUnavailableReading(holder.itemView.context, endpointState))
                } else if (devices.isEmpty()) {
                    holder.readings.addView(createEmptyReading(holder.itemView.context))
                } else {
                    devices.take(5).forEach { holder.readings.addView(createReadingRow(holder.itemView.context, it)) }
                    if (devices.size > 5) {
                        holder.readings.addView(createOverflowReading(holder.itemView.context, devices.size - 5))
                    }
                }
            }
        }

        private fun bindSwitch(
            holder: ControlModuleViewHolder,
            module: Module,
            endpointState: ManagedEndpointState
        ) {
            val isPending = module.id in pending
            val endpointConfigured = module.deviceList.any { it.ipAddress.isNotBlank() }
            val endpointAvailable = endpointState == ManagedEndpointState.AVAILABLE
            val enabled = module.value >= 0.5
            val onColor = MaterialColors.getColor(holder.switchState, com.google.android.material.R.attr.colorTertiary)
            val offColor = MaterialColors.getColor(holder.switchState, com.google.android.material.R.attr.colorOnSurfaceVariant)

            holder.switchControl.setOnCheckedChangeListener(null)
            holder.switchControl.isChecked = enabled
            holder.switchControl.isEnabled = endpointConfigured && endpointAvailable && !isPending
            holder.switchProgress.isVisible = isPending
            holder.switchState.text = when {
                !endpointConfigured -> "Endpoint required"
                !endpointAvailable -> endpointState.label
                isPending -> "Sending…"
                enabled -> "On"
                else -> "Off"
            }
            holder.switchState.setTextColor(if (enabled && endpointConfigured) onColor else offColor)
            holder.switchControl.setOnCheckedChangeListener { button, checked ->
                button.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onSwitchChanged(module, checked)
            }
        }

        override fun getItemCount(): Int = modules.size

        fun submit(items: List<Module>, pendingModuleIds: Set<String>) {
            modules = items.sortedBy { it.displayOrder }
            pending = pendingModuleIds.toSet()
            managersById = ManagerDiscovery.snapshot().associateBy { it.managerId }
            notifyDataSetChanged()
        }
    }

    private class ControlModuleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val type: TextView = view.findViewById(R.id.controlModuleType)
        val title: TextView = view.findViewById(R.id.controlModuleTitle)
        val description: TextView = view.findViewById(R.id.controlModuleDescription)
        val readings: LinearLayout = view.findViewById(R.id.controlReadings)
        val switchArea: LinearLayout = view.findViewById(R.id.controlSwitchArea)
        val switchState: TextView = view.findViewById(R.id.controlSwitchState)
        val switchProgress: ProgressBar = view.findViewById(R.id.controlSwitchProgress)
        val switchControl: MaterialSwitch = view.findViewById(R.id.controlSwitch)
    }

    private fun createReadingRow(context: Context, device: DeviceEntity): View {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())

            addView(TextView(context).apply {
                val sensorName = device.name.ifBlank { readableSensorName(device.type) }
                text = device.sourceDeviceName.takeIf { it.isNotBlank() }
                    ?.let { "$sensorName · $it" }
                    ?: sensorName
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = formatSensorValue(device)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
            })
        }
    }

    private fun createEmptyReading(context: Context): View = TextView(context).apply {
        text = "No sensors assigned"
        textSize = 12f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun createUnavailableReading(
        context: Context,
        endpointState: ManagedEndpointState
    ): View = TextView(context).apply {
        text = endpointState.label
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.manager_offline))
    }

    private fun createOverflowReading(context: Context, count: Int): View = TextView(context).apply {
        text = "+$count more sensors"
        textSize = 10f
        setTextColor(ContextCompat.getColor(context, R.color.primary_color))
    }

    private fun formatSensorValue(device: DeviceEntity): String {
        if (device.values.isEmpty()) return "--"
        val unit = device.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        if (device.values.size == 1) return String.format(Locale.US, "%.1f%s", device.values.first(), unit)

        val labels = when (device.type) {
            DeviceType.ROTATION_VECTOR, DeviceType.GAME_ROTATION_VECTOR,
            DeviceType.GEOMAGNETIC_ROTATION_VECTOR -> listOf("X", "Y", "Z", "W")
            else -> listOf("X", "Y", "Z", "V4", "V5", "V6")
        }
        return device.values.take(4).mapIndexed { index, value ->
            "${labels.getOrElse(index) { "V${index + 1}" }} ${String.format(Locale.US, "%.1f", value)}"
        }.joinToString(" · ") + unit
    }

    private fun readableSensorName(type: DeviceType): String = type.name
        .lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    override fun onDestroyView() {
        controlProjectList = null
        controlModuleGrid = null
        controlProjectTitle = null
        controlEmptyState = null
        controlProjectAdapter = null
        controlModuleAdapter = null
        pendingSwitches.clear()
        _binding = null
        super.onDestroyView()
    }
}
