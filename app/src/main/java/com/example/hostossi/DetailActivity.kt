package com.example.hostossi

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hostossi.databinding.ActivityDetailBinding
import com.example.hostossi.databinding.ItemModuleBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.Collections

class DetailActivity : AppCompatActivity() {

    private companion object {
        const val ENDPOINT_TAG = "EndpointPicker"
        const val MANAGER_STATUS_REFRESH_MS = 3_000L
    }

    private lateinit var binding: ActivityDetailBinding
    private var selectedProject : Project?=null
    private var selectedProjectId: String? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 2_500
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
    }
    private lateinit var projectDao : ProjectDao
    private lateinit var moduleDao: ModuleDao
    private lateinit var deviceEntityDao: DeviceEntityDao
    private lateinit var moduleAdapter: ModuleAdapter
    private lateinit var moduleTouchHelper: ItemTouchHelper
    private var moduleDragStartPosition = RecyclerView.NO_POSITION
    private var managerStatusJob: Job? = null
    private var managerStatusKnown = false
    private var advertisedManagersById: Map<String, AdvertisedManager> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        selectedProjectId = intent.getStringExtra("EXTRA_PROJECT_ID")

        val project = selectedProjectId?.let{ProjectManager.findProject(it)} //get currently focused project
        if(project != null){
            binding.projectTitle.text = project.name
            selectedProject = project
        }
        else
            binding.projectTitle.text = "Project not found"

        val db = (application as MyApplication).dataBase //get database

        projectDao = db.projectDao() //get interfaces
        moduleDao = db.moduleDao()
        deviceEntityDao = db.peripheralDao()

        setupModuleList()
        updateModulesfromDB()

    }

    override fun onStart() {
        super.onStart()
        managerStatusJob?.cancel()
        managerStatusJob = lifecycleScope.launch {
            while (true) {
                val result = if (ClientEndpointResolver.hasConfiguredClient(this@DetailActivity)) {
                    KtorServer.fetchAdvertisedManagers(this@DetailActivity, logResult = false)
                } else {
                    Result.failure(IllegalStateException("No Client configured"))
                }
                updateManagerAvailability(
                    isKnown = result.isSuccess,
                    managers = result.getOrDefault(emptyList())
                )
                delay(MANAGER_STATUS_REFRESH_MS)
            }
        }
    }

    override fun onStop() {
        managerStatusJob?.cancel()
        managerStatusJob = null
        super.onStop()
    }

    private fun updateManagerAvailability(isKnown: Boolean, managers: List<AdvertisedManager>) {
        val nextManagers = managers.associateBy { it.managerId }
        if (managerStatusKnown == isKnown && sameManagerInventory(advertisedManagersById, nextManagers)) return
        managerStatusKnown = isKnown
        advertisedManagersById = nextManagers
        if (::moduleAdapter.isInitialized) moduleAdapter.notifyDataSetChanged()
    }

    private fun sameManagerInventory(
        current: Map<String, AdvertisedManager>,
        next: Map<String, AdvertisedManager>
    ): Boolean = current.keys == next.keys && current.all { (id, manager) ->
        val candidate = next[id] ?: return@all false
        manager.title == candidate.title &&
            manager.endpoint == candidate.endpoint &&
            manager.devices == candidate.devices
    }

    private fun managerAvailability(device: DeviceEntity): ManagedEndpointState =
        device.managedEndpointState(advertisedManagersById, managerStatusKnown)

    suspend fun fetchSensors(): List<AndroidSensorDescriptor> {
        val localDeviceId = DeviceIdentity.id(this)
        val localDeviceName = DeviceIdentity.name()
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val localSensors = sensorManager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            AndroidSensorDescriptor(
                name = sensor.name,
                sensorType = sensor.type,
                type = DeviceType.fromAndroidSensorType(sensor.type),
                sourceDeviceId = localDeviceId,
                sourceDeviceName = localDeviceName
            )
        }
        val clientSensors = if (ClientEndpointResolver.hasConfiguredClient(this)) {
            ClientEndpointResolver.withFallback(this) { endpoint ->
                client.get("http://${endpoint.address}:8080/sensors").body<List<AndroidSensorDescriptor>>()
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        return (localSensors + clientSensors).distinctBy { it.sourceDeviceId to it.sensorType }
    }

    fun addNewGenericModule(view: View) {
        SnackbarUtils.showModernSnackbar(binding.root, "Generic Module added!", anchorView = binding.expandableFab)

        val genericModule = Module(displayOrder = selectedProject?.moduleList?.size ?: 0)
        genericModule.moduleTitle = "empty Generic"
        genericModule.description = "A new empty generic module. Select an Endpoint (WIFI-capable MCU) to select one of it's connected sensors."
        genericModule.moduleType = "Generic"
        genericModule.projectId = selectedProject!!.id

        lifecycleScope.launch(Dispatchers.IO){
            moduleDao.insertModule(genericModule)
            KtorServer.syncProjectsToClient(this@DetailActivity)
        }

    }

    fun addNewSwitchModule(view: View) {
        SnackbarUtils.showModernSnackbar(binding.root, "Switch Module added!", anchorView = binding.expandableFab)

        val switchModule = Module(displayOrder = selectedProject?.moduleList?.size ?: 0)
        switchModule.moduleTitle = "empty Switch"
        switchModule.description = "A new empty switch module. Select an Endpoint (WIFI-capable MCU) to select one of it's connected switches."
        switchModule.moduleType = "Switch"
        switchModule.projectId = selectedProject!!.id

        lifecycleScope.launch(Dispatchers.IO){
            moduleDao.insertModule(switchModule)
            KtorServer.syncProjectsToClient(this@DetailActivity)
        }
    }

    fun updateModulesfromDB(){
        val projectId = selectedProjectId ?: return
        lifecycleScope.launch {
            projectDao.getProjectWithModules(projectId).collect { projectWithModules ->
                val project = projectWithModules?.project ?: return@collect
                val modulesWithDevices = projectWithModules.modules

                // Update global state
                var globalProject = ProjectManager.findProject(projectId)
                if (globalProject == null) {
                    globalProject = project.copy()
                    ProjectManager.projectList.add(globalProject)
                }

                withContext(Dispatchers.Main) {
                    globalProject.name = project.name
                    globalProject.description = project.description
                    
                    // Sync modules list in memory
                    val updatedModules = modulesWithDevices.sortedBy { it.module.displayOrder }.map { mwd ->
                        val freshModule = mwd.module
                        val existingModule = globalProject.moduleList.find { it.id == freshModule.id }
                        
                        val m = if (existingModule != null) {
                            existingModule.moduleTitle = freshModule.moduleTitle
                            existingModule.description = freshModule.description
                            existingModule.moduleType = freshModule.moduleType
                            existingModule.value = freshModule.value
                            existingModule.unit = freshModule.unit
                            existingModule
                        } else {
                            freshModule.copy()
                        }
                        
                        // Map units if missing
                        m.deviceList = mwd.devices.map { device ->
                            if (device.unit.isEmpty()) {
                                device.unit = when(device.type) {
                                    DeviceType.LIGHT_SENSOR -> "lx"
                                    DeviceType.PRESSURE -> "hPa"
                                    DeviceType.AMBIENT_TEMPERATURE -> "°C"
                                    DeviceType.RELATIVE_HUMIDITY -> "%"
                                    DeviceType.STEP_COUNTER -> "steps"
                                    DeviceType.HEART_RATE -> "bpm"
                                    else -> ""
                                }
                            }
                            device
                        }.toMutableList()
                        m
                    }
                    globalProject.moduleList.clear()
                    globalProject.moduleList.addAll(updatedModules)
                    
                    selectedProject = globalProject
                    binding.projectTitle.text = globalProject.name
                    moduleAdapter.submit(globalProject.moduleList)
                }

                KtorServer.refreshHostSensorListeners(this@DetailActivity)
            }
        }
    }

    private fun styleModuleCard(itemModuleBinding: ItemModuleBinding, moduleType: String) {
        val isSwitch = moduleType.equals("Switch", ignoreCase = true)
        val backgroundColor = itemModuleBinding.moduleCard.cardBackgroundColor.defaultColor
        val badgeBackgroundColor = if (isSwitch) R.color.switch_badge_background else R.color.generic_badge_background
        val badgeTextColor = if (isSwitch) R.color.switch_badge_text else R.color.generic_badge_text
        val icon = if (isSwitch) R.drawable.ic_switch else R.drawable.ic_memory
        val label = if (isSwitch) "SWITCH" else "GENERIC"

        itemModuleBinding.moduleCard.setCardBackgroundColor(backgroundColor)
        itemModuleBinding.moduleCard.setStrokeColor(ContextCompat.getColor(this, badgeBackgroundColor))
        itemModuleBinding.labelModuleType.setCardBackgroundColor(ContextCompat.getColor(this, badgeBackgroundColor))
        itemModuleBinding.labelModuleType.setStrokeColor(backgroundColor)
        itemModuleBinding.iconDisplay.setImageResource(icon)
        itemModuleBinding.iconDisplay.setColorFilter(ContextCompat.getColor(this, badgeTextColor))
        itemModuleBinding.moduleTypeLabel.text = label
        itemModuleBinding.moduleTypeLabel.setTextColor(ContextCompat.getColor(this, badgeTextColor))
    }

    fun addOnClickListeners(itemModuleBinding: ItemModuleBinding, genericModule: Module){
        itemModuleBinding.moduleCard.setOnClickListener {
            var visible = itemModuleBinding.moduleDescription.visibility

            if (visible == View.VISIBLE) {
                itemModuleBinding.moduleDescription.visibility = View.GONE
                itemModuleBinding.managerBadge.isVisible = false
                setModuleActionsVisible(itemModuleBinding, genericModule.moduleType, false)
            } else {
                itemModuleBinding.moduleDescription.visibility = View.VISIBLE
                itemModuleBinding.managerBadge.isVisible =
                    itemModuleBinding.managerIndicatorIcon.isVisible
                setModuleActionsVisible(itemModuleBinding, genericModule.moduleType, true)
            }
        }

        itemModuleBinding.verticalMenu.setOnClickListener {
            val popupMenu = PopupMenu(this@DetailActivity, itemModuleBinding.verticalMenu)
            popupMenu.menu.add("Edit")
            popupMenu.menu.add("Delete")
            popupMenu.show()

            popupMenu.setOnMenuItemClickListener { item ->
                var menuText: String = item.title as String

                if (menuText == "Delete") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        moduleDao.delete(genericModule)
                        KtorServer.syncProjectsToClient(this@DetailActivity)
                    }
                    true
                } else if (menuText == "Edit") {
                    val isSwitch = genericModule.moduleType.equals("Switch", ignoreCase = true)

                    itemModuleBinding.moduleTitle.isVisible = false
                    itemModuleBinding.moduleDescription.isVisible = false
                    itemModuleBinding.managerBadge.isVisible = false
                    itemModuleBinding.verticalMenu.isVisible = false
                    itemModuleBinding.scanWIFI.isVisible = false
                    if(!isSwitch) itemModuleBinding.selectSensor.isVisible = false
                    itemModuleBinding.editableModuleTitle.apply {
                        isVisible = true
                        setText(itemModuleBinding.moduleTitle.text)
                        requestFocus()

                        post {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                            setSelection(text.length)
                        }

                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_DONE) {
                                clearFocus()
                                true
                            } else false
                        }

                        setOnFocusChangeListener { _, hasFocus ->
                            if (!hasFocus) {
                                val newName = text.toString()
                                if (newName.isNotBlank()) {
                                    genericModule.moduleTitle = newName
                                    itemModuleBinding.moduleTitle.text = newName
                                    ProjectManager.findProject(selectedProjectId ?: "")
                                        ?.moduleList
                                        ?.find { it.id == genericModule.id }
                                        ?.moduleTitle = newName
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        moduleDao.updateModule(genericModule)
                                        KtorServer.syncProjectsToClient(this@DetailActivity)
                                    }
                                }
                                isVisible = false
                                itemModuleBinding.moduleTitle.isVisible = true
                                itemModuleBinding.moduleDescription.isVisible = true
                                itemModuleBinding.managerBadge.isVisible =
                                    itemModuleBinding.managerIndicatorIcon.isVisible
                                itemModuleBinding.verticalMenu.isVisible = true
                                itemModuleBinding.scanWIFI.isVisible = true
                                if(!isSwitch) itemModuleBinding.selectSensor.isVisible = true
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            }

        }
//region onBoard sensor selection
        itemModuleBinding.selectSensor.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val sensors = fetchSensors()
                    Log.d("Sensors", sensors.toString())
                    if (sensors.isEmpty()) {
                        SnackbarUtils.showModernSnackbar(binding.root, "No sensors are available on this device or the client.", anchorView = binding.expandableFab)
                        return@launch
                    }
                    Log.d("selectSensor", "showing bottom sheet for: " + genericModule.id)
                    showSensorSelectionBottomSheet(sensors, genericModule, itemModuleBinding)

                } catch (e: Exception) {
                    SnackbarUtils.showModernSnackbar(binding.root, "Failed to fetch sensors: ${e.localizedMessage}", anchorView = binding.expandableFab)
                    Log.e("Error", "Failed to fetch sensors: ${e.message}")
                }
            }
        }

        itemModuleBinding.scanWIFI.setOnClickListener {
            showEndpointDialog(genericModule)
        }
    }

    private fun showEndpointDialog(module: Module) {
        val loading = MaterialAlertDialogBuilder(this)
            .setTitle("ESP8266 managers")
            .setMessage("Searching on the Client network…")
            .setNegativeButton("Cancel", null)
            .show()

        lifecycleScope.launch {
            val result = KtorServer.fetchAdvertisedManagers(this@DetailActivity)
            if (isFinishing || isDestroyed) return@launch
            loading.dismiss()
            val managers = result.getOrDefault(emptyList())
            Log.d(ENDPOINT_TAG, "Endpoint picker received ${managers.size} manager(s)")
            showManagerSelectionBottomSheet(module, managers, result.isFailure)
        }
    }

    private fun showManagerSelectionBottomSheet(
        module: Module,
        managers: List<AdvertisedManager>,
        clientUnavailable: Boolean
    ) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_manager_selection, binding.root, false)
        sheet.setContentView(view)

        val list = view.findViewById<android.widget.LinearLayout>(R.id.managerSelectionList)
        val title = view.findViewById<android.widget.TextView>(R.id.managerSelectionTitle)
        val subtitle = view.findViewById<android.widget.TextView>(R.id.managerSelectionSubtitle)
        val count = view.findViewById<Chip>(R.id.managerOnlineCount)
        val empty = view.findViewById<android.widget.TextView>(R.id.managerSelectionEmpty)

        title.text = if (module.moduleType.equals("Switch", ignoreCase = true)) {
            "Select switch manager"
        } else {
            "Select sensor manager"
        }
        count.text = "${managers.size} ONLINE"
        empty.isVisible = managers.isEmpty()
        list.isVisible = managers.isNotEmpty()
        if (managers.isEmpty()) {
            subtitle.text = if (clientUnavailable) {
                "The Client could not be reached"
            } else {
                "No managers are advertising on the Client network"
            }
        }

        managers.forEach { manager ->
            val managerView = layoutInflater.inflate(R.layout.item_advertised_manager, list, false)
            bindAdvertisedManagerCard(managerView, manager, module)
            managerView.setOnClickListener {
                sheet.dismiss()
                showManagerDeviceDialog(module, manager)
            }
            list.addView(managerView)
        }

        view.findViewById<View>(R.id.managerSelectionCancel).setOnClickListener { sheet.dismiss() }
        view.findViewById<View>(R.id.managerSelectionManual).setOnClickListener {
            sheet.dismiss()
            showManualEndpointDialog(module)
        }
        sheet.show()
    }

    private fun bindAdvertisedManagerCard(
        view: View,
        manager: AdvertisedManager,
        module: Module
    ) {
        val wantsSwitch = module.moduleType.equals("Switch", ignoreCase = true)
        val accentBackground = ContextCompat.getColor(
            this,
            if (wantsSwitch) R.color.switch_badge_background else R.color.generic_badge_background
        )
        val accentForeground = ContextCompat.getColor(
            this,
            if (wantsSwitch) R.color.switch_badge_text else R.color.generic_badge_text
        )
        view.findViewById<MaterialCardView>(R.id.managerIconCard).setCardBackgroundColor(accentBackground)
        view.findViewById<android.widget.ImageView>(R.id.managerIcon).setColorFilter(accentForeground)
        (view as MaterialCardView).setStrokeColor(ColorUtils.setAlphaComponent(accentForeground, 100))
        view.findViewById<android.widget.TextView>(R.id.advertisedManagerTitle).text = manager.title
        view.findViewById<android.widget.TextView>(R.id.advertisedManagerEndpoint).apply {
            text = manager.endpoint
            setTextColor(accentForeground)
        }

        val sensors = manager.devices.count { !it.isSwitch }
        val switches = manager.devices.count { it.isSwitch }
        view.findViewById<android.widget.TextView>(R.id.advertisedManagerSummary).text =
            "$sensors sensor${if (sensors == 1) "" else "s"} · $switches switch${if (switches == 1) "" else "es"}"

        val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.advertisedManagerDevices)
        manager.devices.forEach { peripheral ->
            chipGroup.addView(Chip(this).apply {
                text = if (peripheral.isSwitch) "Switch · ${peripheral.name}" else "Sensor · ${peripheral.name}"
                isCheckable = false
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@DetailActivity,
                        if (peripheral.isSwitch) R.color.switch_badge_background else R.color.generic_badge_background
                    )
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@DetailActivity,
                        if (peripheral.isSwitch) R.color.switch_badge_text else R.color.generic_badge_text
                    )
                )
            })
        }
        if (manager.devices.isEmpty()) {
            chipGroup.addView(Chip(this).apply {
                text = "No devices advertised"
                isCheckable = false
            })
        }
    }

    private fun showManagerDeviceDialog(module: Module, manager: AdvertisedManager) {
        val needsSwitch = module.moduleType.equals("Switch", ignoreCase = true)
        val compatibleDevices = manager.devices.filter { it.isSwitch == needsSwitch }
        if (compatibleDevices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(manager.title)
                .setMessage(
                    if (needsSwitch) "This manager does not advertise any switches."
                    else "This manager does not advertise any sensors."
                )
                .setPositiveButton("Back") { _, _ -> showEndpointDialog(module) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_manager_device_selection, binding.root, false)
        sheet.setContentView(view)
        val accentBackground = ContextCompat.getColor(
            this,
            if (needsSwitch) R.color.switch_badge_background else R.color.generic_badge_background
        )
        val accentForeground = ContextCompat.getColor(
            this,
            if (needsSwitch) R.color.switch_badge_text else R.color.generic_badge_text
        )
        view.findViewById<MaterialCardView>(R.id.deviceSelectionManagerIconCard)
            .setCardBackgroundColor(accentBackground)
        view.findViewById<android.widget.ImageView>(R.id.deviceSelectionManagerIcon)
            .setColorFilter(accentForeground)
        view.findViewById<android.widget.TextView>(R.id.deviceSelectionManagerTitle).text = manager.title
        view.findViewById<android.widget.TextView>(R.id.deviceSelectionManagerEndpoint).apply {
            text = manager.endpoint
            setTextColor(accentForeground)
        }
        view.findViewById<Chip>(R.id.deviceSelectionKind).apply {
            text = if (needsSwitch) "SWITCHES" else "SENSORS"
            chipBackgroundColor = ColorStateList.valueOf(accentBackground)
            setTextColor(accentForeground)
        }
        view.findViewById<android.widget.TextView>(R.id.deviceSelectionPrompt).text =
            if (needsSwitch) "Choose a switch channel" else "Choose a sensor channel"

        val list = view.findViewById<android.widget.LinearLayout>(R.id.deviceSelectionList)
        compatibleDevices.forEach { peripheral ->
            val deviceView = layoutInflater.inflate(R.layout.item_advertised_device, list, false)
            bindAdvertisedDeviceCard(deviceView, peripheral)
            deviceView.setOnClickListener {
                Log.d(
                    ENDPOINT_TAG,
                    "Selected ${peripheral.kind} '${peripheral.name}' (${peripheral.id}) " +
                        "from manager ${manager.managerId}"
                )
                sheet.dismiss()
                saveEndpoint(
                    module = module,
                    address = managerDeviceEndpoint(manager, peripheral),
                    managerId = manager.managerId,
                    managerTitle = manager.title,
                    peripheral = peripheral,
                    managerDevices = manager.devices
                )
            }
            list.addView(deviceView)
        }
        view.findViewById<View>(R.id.deviceSelectionBack).setOnClickListener {
            sheet.dismiss()
            showEndpointDialog(module)
        }
        sheet.show()
    }

    private fun bindAdvertisedDeviceCard(view: View, peripheral: AdvertisedPeripheral) {
        val backgroundColor = ContextCompat.getColor(
            this,
            if (peripheral.isSwitch) R.color.switch_badge_background else R.color.generic_badge_background
        )
        val foregroundColor = ContextCompat.getColor(
            this,
            if (peripheral.isSwitch) R.color.switch_badge_text else R.color.generic_badge_text
        )
        (view as MaterialCardView).setStrokeColor(ColorUtils.setAlphaComponent(foregroundColor, 90))
        view.findViewById<MaterialCardView>(R.id.advertisedDeviceIconCard)
            .setCardBackgroundColor(backgroundColor)
        view.findViewById<android.widget.ImageView>(R.id.advertisedDeviceIcon).apply {
            setImageResource(if (peripheral.isSwitch) R.drawable.ic_switch else R.drawable.ic_memory)
            setColorFilter(foregroundColor)
        }
        view.findViewById<android.widget.TextView>(R.id.advertisedDeviceName).text = peripheral.name
        view.findViewById<android.widget.TextView>(R.id.advertisedDeviceDetails).text = buildString {
            append(peripheral.type.replace('_', ' '))
            if (peripheral.unit.isNotBlank()) append(" · ").append(peripheral.unit)
        }
        view.findViewById<android.widget.TextView>(R.id.advertisedDevicePath).apply {
            text = peripheral.endpointPath.ifBlank {
                if (peripheral.isSwitch) "/relay" else "/sensor/${peripheral.id}"
            }
            setTextColor(foregroundColor)
        }
    }

    private fun managerDeviceEndpoint(
        manager: AdvertisedManager,
        peripheral: AdvertisedPeripheral
    ): String {
        val path = peripheral.endpointPath.trim()
            .takeIf { it.isNotBlank() }
            ?: if (peripheral.isSwitch) "/relay" else "/sensor/${peripheral.id}"
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return "http://${manager.endpoint}/${path.trimStart('/')}"
    }

    private fun showManualEndpointDialog(module: Module) {
        val existing = module.deviceList.firstOrNull {
            it.connectionType == ConnectionType.WIFI || it.type == DeviceType.SWITCH
        }
        val input = EditText(this).apply {
            hint = "192.168.1.42 or http://device.local/custom-endpoint"
            setText(existing?.ipAddress.orEmpty())
            setSingleLine(true)
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("ESP8266 endpoint")
            .setMessage("An IP sends POST requests to /relay. A complete URL is used unchanged.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text.toString().trim()
                if (address.isBlank()) {
                    input.error = "Enter an IP address or URL"
                    return@setOnClickListener
                }
                saveEndpoint(module, address, existing?.managerID.orEmpty(), existing?.name ?: "ESP8266")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveEndpoint(
        module: Module,
        address: String,
        managerId: String,
        managerTitle: String,
        peripheral: AdvertisedPeripheral? = null,
        managerDevices: List<AdvertisedPeripheral> = emptyList()
    ) {
        val existing = module.deviceList.firstOrNull {
            it.connectionType == ConnectionType.WIFI || it.type == DeviceType.SWITCH
        }
        val endpoint = existing?.copy(
            managerID = managerId,
            managerPeripheralId = peripheral?.id ?: existing.managerPeripheralId,
            name = peripheral?.name ?: managerTitle,
            description = peripheral?.let { "${it.type} managed by $managerTitle (${it.id})" }
                ?: existing.description,
            type = peripheral?.let {
                if (it.isSwitch) DeviceType.SWITCH else DeviceType.fromString(it.type)
            } ?: existing.type,
            ipAddress = address,
            sourceDeviceId = managerId,
            sourceDeviceName = managerTitle,
            unit = peripheral?.unit ?: existing.unit
        ) ?: DeviceEntity(
            moduleId = module.id,
            managerID = managerId,
            managerPeripheralId = peripheral?.id.orEmpty(),
            name = peripheral?.name ?: managerTitle,
            description = peripheral?.let { "${it.type} managed by $managerTitle (${it.id})" }
                ?: "HTTP endpoint managed by ESP8266",
            type = peripheral?.let {
                if (it.isSwitch) DeviceType.SWITCH else DeviceType.fromString(it.type)
            } ?: if (module.moduleType.equals("Switch", ignoreCase = true)) DeviceType.SWITCH else DeviceType.UNKNOWN,
            connectionType = ConnectionType.WIFI,
            ipAddress = address,
            sourceDeviceId = managerId,
            sourceDeviceName = managerTitle,
            unit = peripheral?.unit.orEmpty()
        )
        module.deviceList.removeAll {
            it.id == endpoint.id ||
                (module.moduleType.equals("Switch", ignoreCase = true) && it.type == DeviceType.SWITCH)
        }
        module.deviceList.add(endpoint)
        if (peripheral != null) {
            val channelNames = managerDevices.joinToString { it.name }
                .ifBlank { peripheral.name }
            val unitSuffix = peripheral.unit.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            module.description = "${peripheral.name} · ${peripheral.type.replace('_', ' ')}$unitSuffix\n" +
                "Managed by $managerTitle · Channels: $channelNames"
            moduleAdapter.notifyDataSetChanged()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            deviceEntityDao.insertDevice(endpoint)
            moduleDao.updateModule(module)
            KtorServer.syncProjectsToClient(this@DetailActivity)
        }
        SnackbarUtils.showModernSnackbar(
            binding.root,
            "${peripheral?.name ?: managerTitle} endpoint saved",
            anchorView = binding.expandableFab
        )
    }

    private fun showSensorSelectionBottomSheet(
        sensors: List<AndroidSensorDescriptor>,
        module: Module,
        itemModuleBinding: ItemModuleBinding
    ) {
        lifecycleScope.launch {
            // Fetch actual devices from DB
            val currentDevices = withContext(Dispatchers.IO) {
                deviceEntityDao.getDevicesForModule(module.id)
            }
            module.deviceList = currentDevices.toMutableList()

            val bottomSheetDialog = BottomSheetDialog(this@DetailActivity)
            val view = layoutInflater.inflate(R.layout.bottom_sheet_sensor_selection, binding.root, false)
            bottomSheetDialog.setContentView(view)

            val sourceChipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sourceDeviceChipGroup)
            val sensorChipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sensorChipGroup)
            val sourceLegend = view.findViewById<android.widget.TextView>(R.id.sourceLegend)
            val sensorListTitle = view.findViewById<android.widget.TextView>(R.id.sensorListTitle)
            val sensorEmptyText = view.findViewById<android.widget.TextView>(R.id.sensorEmptyText)
            val btnAdd = view.findViewById<android.widget.Button>(R.id.btnAddSensors)
            val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancel)

            val selectedSensors = module.deviceList.toMutableList()
            val localDeviceId = DeviceIdentity.id(this@DetailActivity)
            val hostColor = ContextCompat.getColor(this@DetailActivity, R.color.host_color)
            val clientColor = ContextCompat.getColor(this@DetailActivity, R.color.client_color)
            val legacySourceId = sensors.firstOrNull { it.sourceDeviceId != localDeviceId }
                ?.sourceDeviceId
                ?: localDeviceId

            fun matches(device: DeviceEntity, sensor: AndroidSensorDescriptor): Boolean {
                if (device.connectionType != ConnectionType.ANDROID || device.sensorType != sensor.sensorType) return false
                return device.sourceDeviceId.ifBlank { legacySourceId } == sensor.sourceDeviceId
            }

            fun applySourcePalette(chip: Chip, sourceColor: Int) {
                chip.chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(sourceColor, ColorUtils.setAlphaComponent(sourceColor, 36))
                )
                chip.setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(Color.WHITE, sourceColor)
                    )
                )
                chip.chipStrokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(sourceColor, 150))
                chip.chipStrokeWidth = resources.displayMetrics.density
            }

            val legendText = SpannableString("HOST PHONE  •  CLIENT PHONE")
            legendText.setSpan(
                ForegroundColorSpan(hostColor),
                0,
                "HOST PHONE".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val clientLegendStart = legendText.indexOf("CLIENT PHONE")
            legendText.setSpan(
                ForegroundColorSpan(clientColor),
                clientLegendStart,
                legendText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sourceLegend.text = legendText

            fun renderSensorsForSource(
                sourceName: String,
                role: String,
                sourceColor: Int,
                sourceSensors: List<AndroidSensorDescriptor>
            ) {
                sensorChipGroup.removeAllViews()
                sensorListTitle.text = "$role PHONE · $sourceName"
                sensorListTitle.setTextColor(sourceColor)
                sensorEmptyText.isVisible = sourceSensors.isEmpty()

                sourceSensors.sortedBy { it.name.lowercase() }.forEach { sensor ->
                    val sensorChip = Chip(this@DetailActivity).apply {
                        text = sensor.name
                        contentDescription = "${sensor.name} on $role phone $sourceName"
                        isCheckable = true
                        isCheckedIconVisible = true
                        applySourcePalette(this, sourceColor)
                        isChecked = selectedSensors.any { matches(it, sensor) }

                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked && selectedSensors.none { matches(it, sensor) }) {
                                selectedSensors.add(
                                    DeviceEntity(
                                        name = sensor.name,
                                        moduleId = module.id,
                                        type = sensor.type,
                                        connectionType = ConnectionType.ANDROID,
                                        sensorType = sensor.sensorType,
                                        sourceDeviceId = sensor.sourceDeviceId,
                                        sourceDeviceName = sensor.sourceDeviceName,
                                        unit = sensor.unit
                                    )
                                )
                            } else if (!isChecked) {
                                selectedSensors.removeAll { matches(it, sensor) }
                            }
                        }
                    }
                    sensorChipGroup.addView(sensorChip)
                }
            }

            fun addSourceOption(
                sourceName: String,
                role: String,
                sourceColor: Int,
                sourceSensors: List<AndroidSensorDescriptor>,
                enabled: Boolean = true
            ) {
                val sourceChip = Chip(this@DetailActivity).apply {
                    text = "$role · $sourceName"
                    contentDescription = "$role phone, $sourceName"
                    isCheckable = enabled
                    isCheckedIconVisible = false
                    isEnabled = enabled
                    applySourcePalette(this, sourceColor)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) renderSensorsForSource(sourceName, role, sourceColor, sourceSensors)
                    }
                }
                sourceChipGroup.addView(sourceChip)
            }

            val hostSensors = sensors.filter { it.sourceDeviceId == localDeviceId }
            addSourceOption(DeviceIdentity.name(), "HOST", hostColor, hostSensors)

            val clientSensorGroups = sensors
                .filter { it.sourceDeviceId != localDeviceId }
                .groupBy { it.sourceDeviceId }
            if (clientSensorGroups.isEmpty()) {
                val state = if (ClientEndpointResolver.hasConfiguredClient(this@DetailActivity)) "Unavailable" else "Not configured"
                addSourceOption(state, "CLIENT", clientColor, emptyList(), enabled = false)
            } else {
                clientSensorGroups.forEach { (_, clientSensors) ->
                    addSourceOption(
                        clientSensors.firstOrNull()?.sourceDeviceName ?: "Android device",
                        "CLIENT",
                        clientColor,
                        clientSensors
                    )
                }
            }

            btnCancel.setOnClickListener { bottomSheetDialog.dismiss() }

            btnAdd.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    // Update DB
                    deviceEntityDao.deleteDevicesForModule(module.id)
                    selectedSensors.forEach { 
                        deviceEntityDao.insertDevice(it)
                    }
                    
                    withContext(Dispatchers.Main) {
                        module.deviceList = selectedSensors
                        module.description = "Sensors: " + selectedSensors.joinToString { device ->
                            val source = device.sourceDeviceName.takeIf { it.isNotBlank() }
                            if (source == null) device.name else "${device.name} ($source)"
                        }
                        itemModuleBinding.moduleDescription.text = module.description
                        
                        SnackbarUtils.showModernSnackbar(binding.root, "Added ${selectedSensors.size} sensors!", anchorView = binding.expandableFab)
                        bottomSheetDialog.dismiss()
                    }
                    
                    // Persist the updated module description
                    moduleDao.updateModule(module)

                    // Sync to client
                    KtorServer.syncProjectsToClient(this@DetailActivity)
                }
            }

            bottomSheetDialog.show()
        }
    }

    private fun setupModuleList() {
        moduleAdapter = ModuleAdapter()
        binding.moduleList.apply {
            layoutManager = LinearLayoutManager(this@DetailActivity)
            adapter = moduleAdapter
            setHasFixedSize(false)
            itemAnimator?.moveDuration = 220
            itemAnimator?.changeDuration = 160
        }

        moduleTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                moduleAdapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder is ModuleViewHolder) {
                    moduleDragStartPosition = viewHolder.bindingAdapterPosition
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.binding.moduleCard.strokeWidth = resources.getDimensionPixelSize(R.dimen.drag_target_stroke)
                    viewHolder.itemView.animate()
                        .scaleX(1.035f).scaleY(1.035f).alpha(0.92f)
                        .translationZ(resources.getDimension(R.dimen.drag_elevation))
                        .setDuration(150).start()
                    SnackbarUtils.showModernSnackbar(
                        binding.root,
                        "Move the module, then release to save",
                        anchorView = binding.expandableFab
                    )
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                (viewHolder as? ModuleViewHolder)?.binding?.moduleCard?.strokeWidth = 0
                viewHolder.itemView.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f).translationZ(0f)
                    .setDuration(180).start()
                val endPosition = viewHolder.bindingAdapterPosition
                if (moduleDragStartPosition != RecyclerView.NO_POSITION &&
                    endPosition != RecyclerView.NO_POSITION &&
                    moduleDragStartPosition != endPosition
                ) {
                    persistModuleOrder()
                }
                moduleDragStartPosition = RecyclerView.NO_POSITION
            }
        }).also { it.attachToRecyclerView(binding.moduleList) }
    }

    private fun persistModuleOrder() {
        val orderedModules = moduleAdapter.orderedModules()
            .onEachIndexed { index, module -> module.displayOrder = index }
        selectedProject?.moduleList?.clear()
        selectedProject?.moduleList?.addAll(orderedModules)
        lifecycleScope.launch(Dispatchers.IO) {
            moduleDao.updateModuleOrder(orderedModules)
            KtorServer.syncProjectsToClient(this@DetailActivity)
        }
        SnackbarUtils.showModernSnackbar(
            binding.root,
            "Module order saved",
            anchorView = binding.expandableFab
        )
    }

    private inner class ModuleViewHolder(val binding: ItemModuleBinding) :
        RecyclerView.ViewHolder(binding.root)

    private inner class ModuleAdapter : RecyclerView.Adapter<ModuleViewHolder>() {
        private val items = mutableListOf<Module>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder =
            ModuleViewHolder(ItemModuleBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
            val module = items[position]
            holder.binding.apply {
                moduleTitle.text = module.moduleTitle
                moduleDescription.text = module.description
                moduleCard.strokeWidth = 0
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
                editableModuleTitle.isVisible = false
                moduleTitle.isVisible = true
                moduleDescription.isVisible = false
                verticalMenu.isVisible = true
                selectSensor.isVisible = false
                scanWIFI.isVisible = false
                bindModuleManagerIdentity(this, module)
                styleModuleCard(this, module.moduleType)
                addOnClickListeners(this, module)
                moduleCard.setOnLongClickListener {
                    moduleTouchHelper.startDrag(holder)
                    true
                }
            }
        }

        override fun getItemCount() = items.size

        fun submit(modules: List<Module>) {
            val next = modules.sortedBy { it.displayOrder }
            val previous = items.toList()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].id == next[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == next[newItemPosition]
            })
            items.clear()
            items.addAll(next)
            diff.dispatchUpdatesTo(this)
        }

        fun move(from: Int, to: Int) {
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        fun orderedModules(): List<Module> = items.toList()
    }

    private fun bindModuleManagerIdentity(binding: ItemModuleBinding, module: Module) {
        val managedDevice = module.deviceList.firstOrNull {
            it.connectionType == ConnectionType.WIFI && it.managerID.isNotBlank()
        }
        binding.managerIndicatorIcon.isVisible = managedDevice != null
        binding.managerStatusDot.isVisible = managedDevice != null
        binding.managerBadge.isVisible = false
        if (managedDevice == null) return

        val isSwitch = managedDevice.type == DeviceType.SWITCH
        val background = ContextCompat.getColor(
            this,
            if (isSwitch) R.color.switch_badge_background else R.color.generic_badge_background
        )
        val foreground = ContextCompat.getColor(
            this,
            if (isSwitch) R.color.switch_badge_text else R.color.generic_badge_text
        )
        val managerName = managedDevice.sourceDeviceName.ifBlank {
            managedDevice.managerID.take(12)
        }
        val availability = managerAvailability(managedDevice)
        val statusColor = ContextCompat.getColor(
            this,
            when (availability) {
                ManagedEndpointState.AVAILABLE -> R.color.manager_online
                ManagedEndpointState.UNKNOWN -> R.color.manager_unknown
                else -> R.color.manager_offline
            }
        )
        val badgeLabel = "MANAGER | $managerName | ${availability.label}"
        val styledBadgeLabel = SpannableString(badgeLabel).apply {
            setSpan(
                ForegroundColorSpan(statusColor),
                badgeLabel.length - availability.label.length,
                badgeLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.managerBadge.setCardBackgroundColor(background)
        binding.managerBadgeText.text = styledBadgeLabel
        binding.managerBadgeText.setTextColor(foreground)
        binding.managerIndicatorIcon.setColorFilter(foreground)
        binding.managerStatusDot.setCardBackgroundColor(statusColor)
        binding.managerIndicatorIcon.contentDescription =
            "Managed by $managerName, ${availability.label.lowercase()}"
        if (availability != ManagedEndpointState.AVAILABLE &&
            availability != ManagedEndpointState.UNKNOWN
        ) {
            binding.moduleDescription.text =
                "${module.description}\n\n${availability.explanation}. Select Endpoint to assign another channel."
        }
    }

    private fun setModuleActionsVisible(
        itemModuleBinding: ItemModuleBinding,
        moduleType: String,
        isVisible: Boolean
    ) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE

        itemModuleBinding.selectSensor.visibility = if (moduleType.equals("Generic", ignoreCase = true)) visibility else View.GONE
        itemModuleBinding.scanWIFI.visibility = visibility
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

}
