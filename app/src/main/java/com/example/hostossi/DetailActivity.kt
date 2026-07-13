package com.example.hostossi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.ActivityDetailBinding
import com.example.hostossi.databinding.ItemModuleBinding
import com.example.hostossi.databinding.SensorPopupBinding
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

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var sensorPopup: SensorPopupBinding
    private var selectedProject : Project?=null
    private var selectedProjectId: String? = null
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    private lateinit var projectDao : ProjectDao
    private lateinit var moduleDao: ModuleDao
    private lateinit var peripheralDao: PeripheralDao


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityDetailBinding.inflate(layoutInflater)
        sensorPopup = SensorPopupBinding.inflate(layoutInflater)
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
        peripheralDao = db.peripheralDao()

        updateModulesfromDB()

    }

    suspend fun fetchSensors(): List<String> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val clientIpAddress = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
        if (clientIpAddress.isBlank()) return emptyList()

        val response: List<String> = client.get("http://$clientIpAddress:8080/sensors").body()
        return response
    }

    fun addNewGenericModule(view: View) {
        Toast.makeText(this, "Generic Module added!", Toast.LENGTH_SHORT).show()

        val genericModule = Module()
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
        Toast.makeText(this, "Switch Module added!", Toast.LENGTH_SHORT).show()

        val switchModule = Module()
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


            projectDao.getProjectWithModules(projectId).collect { projectWithModules -> //this will get called every time the Database changes
                val project = projectWithModules?.project ?: return@collect
                val modules = projectWithModules.modules

                binding.moduleList.removeAllViews()
                selectedProject = project
                selectedProject!!.moduleList.clear()
                binding.projectTitle.text = project.name

                val json = Json.encodeToString(selectedProject)
                Log.d("json", json)

                for(module in modules) {
                    selectedProject!!.moduleList.add(module) //Refreshes the local module list

                    val itemModuleBinding = ItemModuleBinding.inflate(layoutInflater) //creates new Module item

                    itemModuleBinding.moduleCard.id = View.generateViewId()
                    itemModuleBinding.moduleTitle.text = module.moduleTitle
                    itemModuleBinding.moduleDescription.text = module.description
                    styleModuleCard(itemModuleBinding, module.moduleType)

                    module.peripheralList = peripheralDao.getAllPeripherals() as MutableList<Peripheral> //refresh peripherals

                    withContext(Dispatchers.Main){
                        binding.moduleList.addView(itemModuleBinding.root) //adds the module to the list
                        Log.d("module", "new module added!")

                    }
                    addOnClickListeners(itemModuleBinding, module) //makes the module clickable

                }

            }
        }

    }

    private fun styleModuleCard(itemModuleBinding: ItemModuleBinding, moduleType: String) {
        val isSwitch = moduleType == "Switch"
        val backgroundColor = itemModuleBinding.moduleCard.cardBackgroundColor.defaultColor
        val badgeBackgroundColor = if (isSwitch) R.color.switch_badge_background else R.color.generic_badge_background
        val badgeTextColor = if (isSwitch) R.color.switch_badge_text else R.color.generic_badge_text
        val icon = if (isSwitch) R.drawable.ic_switch else R.drawable.ic_memory
        val label = if (isSwitch) "SWITCH" else "GENERIC"

        itemModuleBinding.moduleCard.setCardBackgroundColor(backgroundColor)
        itemModuleBinding.moduleCard.setStrokeColor(backgroundColor)
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
                    val isSwitch = genericModule.moduleType == "Switch"

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
                        Toast.makeText(this@DetailActivity, "No sensors found. Check client connection.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    Toast.makeText(this@DetailActivity, "Found sensors on client!", Toast.LENGTH_SHORT).show()

                    sensorPopup.root.id = View.generateViewId()

                    for (sensor in sensors) {
                        val checkBox = CheckBox(this@DetailActivity)
                        checkBox.text = sensor
                        checkBox.id = View.generateViewId()
                        sensorPopup.onBoardSensorContainer.addView(checkBox)

                        checkBox.setOnCheckedChangeListener { button, bool ->
                            if (bool) {
                                val Peripheral = Peripheral(peripheralName=checkBox.text.toString())
                                genericModule.selectedPeripherals.add(Peripheral)
                                lifecycleScope.launch(Dispatchers.IO){
                                    peripheralDao.insertPeripheral(Peripheral)
                                }
                            }
                            else{
                                genericModule.selectedPeripherals.remove(Peripheral(peripheralName=checkBox.text.toString()))
                                lifecycleScope.launch(Dispatchers.IO){
                                    peripheralDao.deletePeripheral(Peripheral(peripheralName=checkBox.text.toString()))
                                }

                            }
                        }

                    }

                    binding.root.addView(sensorPopup.root)
                    val params = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_PARENT,
                        ConstraintLayout.LayoutParams.MATCH_PARENT
                    )
                    sensorPopup.root.layoutParams = params
                    sensorPopup.root.visibility = View.VISIBLE

                    sensorPopup.cancelButton.setOnClickListener {
                        binding.root.removeView(sensorPopup.root)
                        genericModule.selectedPeripherals.clear()
                    }
                    sensorPopup.addSensorButton.setOnClickListener {
                        //TODO: write logic for adding peripherals to module
                        Toast.makeText(this@DetailActivity, "Added sensor to module!", Toast.LENGTH_SHORT).show()
                        binding.root.removeView(sensorPopup.root)
                        genericModule.peripheralList = genericModule.selectedPeripherals
                        genericModule.description += "\n" + genericModule.selectedPeripherals.toString()
                        itemModuleBinding.moduleDescription.text = genericModule.description
                        genericModule.selectedPeripherals.clear()
                    }

                } catch (e: Exception) {
                    // Fehlerbehandlung (z.B. Timeout oder falsche IP)
                    Toast.makeText(this@DetailActivity, "Failed to fetch sensors from client!", Toast.LENGTH_SHORT).show()
                    Log.e("Error", "Failed to fetch sensors: ${e.message}")
                }
            }
        }
    }

    private fun setModuleActionsVisible(
        itemModuleBinding: ItemModuleBinding,
        moduleType: String,
        isVisible: Boolean
    ) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE

        itemModuleBinding.selectSensor.visibility = if (moduleType == "Generic") visibility else View.GONE
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
