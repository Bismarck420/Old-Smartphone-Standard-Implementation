package com.example.hostossi

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
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
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.Collections

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private var selectedProject : Project?=null
    private var selectedProjectId: String? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    private lateinit var projectDao : ProjectDao
    private lateinit var moduleDao: ModuleDao
    private lateinit var deviceEntityDao: DeviceEntityDao
    private var lastSyncTime = 0L
    private val syncThrottleMs = 300L


    private var activeSensors = mutableMapOf<String, MeasureableSensor>()
    private lateinit var moduleAdapter: ModuleAdapter
    private lateinit var moduleTouchHelper: ItemTouchHelper
    private var moduleDragStartPosition = RecyclerView.NO_POSITION

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

    suspend fun fetchSensors(): List<AndroidSensorDescriptor> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val clientIpAddress = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
        if (clientIpAddress.isBlank()) return emptyList()

        val response: List<AndroidSensorDescriptor> = client.get("http://$clientIpAddress:8080/sensors").body()
        return response
    }

    fun addNewGenericModule(view: View) {
        SnackbarUtils.showModernSnackbar(binding.root, "Generic Module added!", anchorView = binding.expandableFab)

        val genericModule = Module(displayOrder = selectedProject?.moduleList?.size ?: 0)
        genericModule.moduleTitle = "empty Generic"
        genericModule.description = "A new empty module. Select a sensor from the client smartphone or scan an NFC Tag to fill data."
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
        switchModule.description = "A new empty switch module. Scan an NFC tag to connect the peripheral via bluetooth."
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

                globalProject.moduleList.forEach(::setSensorListeners)
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
                setModuleActionsVisible(itemModuleBinding, genericModule.moduleType, false)
            } else {
                itemModuleBinding.moduleDescription.visibility = View.VISIBLE
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
                        SnackbarUtils.showModernSnackbar(binding.root, "No sensors found. Check client connection.", anchorView = binding.expandableFab)
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
                val endpoint = existing?.copy(ipAddress = address) ?: DeviceEntity(
                    moduleId = module.id,
                    name = "ESP8266",
                    description = "HTTP switch endpoint",
                    type = if (module.moduleType == "Switch") DeviceType.SWITCH else DeviceType.UNKNOWN,
                    connectionType = ConnectionType.WIFI,
                    ipAddress = address
                )
                module.deviceList.removeAll { it.id == endpoint.id || (module.moduleType == "Switch" && it.type == DeviceType.SWITCH) }
                module.deviceList.add(endpoint)
                lifecycleScope.launch(Dispatchers.IO) {
                    deviceEntityDao.insertDevice(endpoint)
                    KtorServer.syncProjectsToClient(this@DetailActivity)
                }
                SnackbarUtils.showModernSnackbar(binding.root, "ESP8266 endpoint saved", anchorView = binding.expandableFab)
                dialog.dismiss()
            }
        }
        dialog.show()
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

            val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sensorChipGroup)
            val btnAdd = view.findViewById<android.widget.Button>(R.id.btnAddSensors)
            val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancel)

            val selectedSensors = module.deviceList.toMutableList()

            sensors.forEach { sensor ->
                val chip = Chip(this@DetailActivity).apply {
                    text = sensor.name
                    isCheckable = true
                    isCheckedIconVisible = true
                    
                    val selectedColor = ContextCompat.getColor(this@DetailActivity, R.color.accent_color)
                    val unselectedColor = ContextCompat.getColor(this@DetailActivity, R.color.generic_badge_background)
                    val textColor = ContextCompat.getColor(this@DetailActivity, R.color.generic_badge_text)
                    
                    chipBackgroundColor = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(selectedColor, unselectedColor)
                    )
                    setTextColor(textColor)

                    // Check if this sensor (by name) is already assigned to this module
                    if (selectedSensors.any { it.sensorType == sensor.sensorType }) {
                        isChecked = true
                    }

                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            if (selectedSensors.none { it.sensorType == sensor.sensorType }) {
                                val type = sensor.type
                                val unit = when(type) {
                                    DeviceType.LIGHT_SENSOR -> "lx"
                                    DeviceType.PRESSURE -> "hPa"
                                    DeviceType.AMBIENT_TEMPERATURE -> "°C"
                                    DeviceType.RELATIVE_HUMIDITY -> "%"
                                    DeviceType.STEP_COUNTER -> "steps"
                                    DeviceType.HEART_RATE -> "bpm"
                                    else -> ""
                                }
                                val newDevice = DeviceEntity(
                                    name = sensor.name,
                                    moduleId = module.id,
                                    type = type,
                                    connectionType = ConnectionType.ANDROID,
                                    sensorType = sensor.sensorType,
                                    unit = unit
                                )
                                selectedSensors.add(newDevice)
                            }
                        } else {
                            selectedSensors.removeAll { it.sensorType == sensor.sensorType }
                        }
                    }
                }
                chipGroup.addView(chip)
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
                        module.description = "Sensors: " + selectedSensors.joinToString { it.name }
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

    fun setSensorListeners(module: Module){
        clearSensorListeners(module)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val hasRemoteClient = preferences.getString("client_IP", "")?.trim().orEmpty().isNotBlank()
        for(device in module.deviceList){ 
            // The linked client phone owns its own SensorManager and streams directly to its dashboard.
            if (device.connectionType == ConnectionType.ANDROID && hasRemoteClient) continue
            val sensor : MeasureableSensor? = DeviceFactory.create(this, device)

            if (sensor != null) {
                sensor.startListening()
                activeSensors[device.id] = sensor

                sensor.setOnSensorValuesChangedListener { value ->
                    // 1. Update global in-memory state (used by website)
                    ProjectManager.updateSensorValues(device.id, value)

                    Log.d("test", "sensorvalue changed")


                    // 2. Throttled sync to the web UI
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSyncTime > syncThrottleMs) {
                        lastSyncTime = currentTime
                        KtorServer.syncSensorValuesToClient(this@DetailActivity, device.id, value)
                        Log.d("test", "synced to client")
                    }
                }
            }
        }
    }

    fun clearSensorListeners(module: Module) {
        for(device in module.deviceList){ //clears all listeners
            activeSensors.remove(device.id)?.stopListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeSensors.values.forEach { it.stopListening() }
        activeSensors.clear()
    }
}
