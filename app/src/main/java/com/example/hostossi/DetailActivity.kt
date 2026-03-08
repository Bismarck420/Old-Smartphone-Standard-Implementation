package com.example.hostossi

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.hostossi.databinding.ActivityDetailBinding
import com.example.hostossi.databinding.ItemModuleBinding
import com.example.hostossi.databinding.SensorPopupBinding
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.hooks.CallSetup.install
import io.ktor.websocket.WebSocketDeflateExtension.Companion.install
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext


class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var sensorPopup: SensorPopupBinding
    private var selectedProject : Project?=null
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

        val projectID = intent.getStringExtra("EXTRA_PROJECT_ID")

        val project = projectID?.let{ProjectManager.findProject(it)}
        if(project != null){
            binding.projectTitle.text = project.name
            selectedProject = project
        }
        else
            binding.projectTitle.text = "Project not found"

        val db = (application as MyApplication).dataBase

        projectDao = db.projectDao()
        moduleDao = db.moduleDao()
        peripheralDao = db.peripheralDao()

        lifecycleScope.launch(Dispatchers.IO){
            moduleDao.getAll().collect { modules ->
                withContext(Dispatchers.Main){
                    updateUIfromDB(modules)
                }
                Log.d("testSmall", "Entered collect!")
            }

        }


    }

    suspend fun fetchSensors(): List<String> {
        // 2. GET-Request absetzen und direkt als Liste empfangen
        val response: List<String> = client.get("http://100.113.232.96:8080/sensors").body()
        return response
    }

    fun addNewOnBoardModule(view: View) {
        val itemModuleBinding = ItemModuleBinding.inflate(layoutInflater)
        itemModuleBinding.moduleCard.id = View.generateViewId()

        val genericModule = Module()
        genericModule.moduleTitle = "empty OnBoard"
        genericModule.description = "A new empty module Select a sensor from the client smartphone or scan an NFC Tag to use it."
        selectedProject?.moduleList?.add(genericModule)

        itemModuleBinding.moduleTitle.text = genericModule.moduleTitle
        itemModuleBinding.moduleDescription.text = genericModule.description
        itemModuleBinding.idLabel.text = "#" + itemModuleBinding.moduleCard.id.toString()
        itemModuleBinding.iconDisplay.setImageResource(R.drawable.ic_pip)


        binding.moduleList.addView(itemModuleBinding.root)

        itemModuleBinding.moduleCard.setOnClickListener {
            var visible = itemModuleBinding.moduleDescription.visibility

            if (visible == View.VISIBLE) {
                itemModuleBinding.moduleDescription.visibility = View.GONE
                itemModuleBinding.selectSensor.visibility = View.GONE
                itemModuleBinding.scanNFC.visibility = View.GONE
            } else {
                itemModuleBinding.moduleDescription.visibility = View.VISIBLE
                itemModuleBinding.selectSensor.visibility = View.VISIBLE
                itemModuleBinding.scanNFC.visibility = View.VISIBLE

            }
        }

        itemModuleBinding.verticalMenu.setOnClickListener {
            val popupMenu = PopupMenu(this@DetailActivity, itemModuleBinding.verticalMenu)
            popupMenu.menu.add("Delete")
            popupMenu.menu.add("Edit")
            popupMenu.show()

            popupMenu.setOnMenuItemClickListener { item ->
                var menuText: String = item.title as String

                if (menuText == "Delete") {
                    binding.moduleList.removeView(itemModuleBinding.root)
                    selectedProject!!.moduleList.remove(genericModule)
                    true
                } else if (menuText == "Edit") {
                    // TODO: make title & description editable
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
        //endregion

        lifecycleScope.launch(Dispatchers.IO){
            moduleDao.insertModule(genericModule)
            projectDao.updateProject(selectedProject!!)
        }

    }

    fun updateUIfromDB(projects: List<Module>){
        val projectModules = selectedProject!!.moduleList

        binding.moduleList.removeAllViews()

        for(module in projectModules) {
            val itemModuleBinding = ItemModuleBinding.inflate(layoutInflater)

            itemModuleBinding.moduleCard.id = View.generateViewId()
            itemModuleBinding.moduleTitle.text = module.moduleTitle
            itemModuleBinding.moduleDescription.text = module.description
            itemModuleBinding.idLabel.text = "#" + itemModuleBinding.moduleCard.id.toString()
            itemModuleBinding.iconDisplay.setImageResource(R.drawable.ic_pip)

            binding.moduleList.addView(itemModuleBinding.root)

            lifecycleScope.launch(Dispatchers.IO) {
                module.peripheralList = peripheralDao.getAllPeripherals() as MutableList<Peripheral>
            }

            itemModuleBinding.moduleCard.setOnClickListener {
                var visible = itemModuleBinding.moduleDescription.visibility

                if (visible == View.VISIBLE) {
                    itemModuleBinding.moduleDescription.visibility = View.GONE
                    itemModuleBinding.selectSensor.visibility = View.GONE
                    itemModuleBinding.scanNFC.visibility = View.GONE
                } else {
                    itemModuleBinding.moduleDescription.visibility = View.VISIBLE
                    itemModuleBinding.selectSensor.visibility = View.VISIBLE
                    itemModuleBinding.scanNFC.visibility = View.VISIBLE

                }
            }

            itemModuleBinding.verticalMenu.setOnClickListener {
                val popupMenu = PopupMenu(this@DetailActivity, itemModuleBinding.verticalMenu)
                popupMenu.menu.add("Delete")
                popupMenu.menu.add("Edit")
                popupMenu.show()

                popupMenu.setOnMenuItemClickListener { item ->
                    var menuText: String = item.title as String

                    if (menuText == "Delete") {
                        binding.moduleList.removeView(itemModuleBinding.root)
                        selectedProject!!.moduleList.remove(module)
                        lifecycleScope.launch(Dispatchers.IO){
                            moduleDao.delete(module)
                            projectDao.updateProject(selectedProject!!)
                        }
                        true
                    } else if (menuText == "Edit") {
                        // TODO: make title & description editable
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
                        Toast.makeText(this@DetailActivity, "Found sensors on client!", Toast.LENGTH_SHORT).show()

                        sensorPopup.root.id = View.generateViewId()

                        for (sensor in sensors) {
                            val checkBox = CheckBox(this@DetailActivity)
                            checkBox.text = sensor
                            checkBox.id = View.generateViewId()
                            sensorPopup.onBoardSensorContainer.addView(checkBox)

                            checkBox.setOnCheckedChangeListener { button, bool ->
                                if (bool) {
                                    module.selectedPeripherals.add(Peripheral(peripheralName=checkBox.text.toString()))
                                }
                                else{
                                    module.selectedPeripherals.remove(Peripheral(peripheralName=checkBox.text.toString()))
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
                            module.selectedPeripherals.clear()
                        }
                        sensorPopup.addSensorButton.setOnClickListener {
                            //TODO: write logic for adding peripherals to module
                            Toast.makeText(this@DetailActivity, "Added sensor to module!", Toast.LENGTH_SHORT).show()
                            binding.root.removeView(sensorPopup.root)
                            module.peripheralList = module.selectedPeripherals
                            module.description += "\n" + module.selectedPeripherals.toString()
                            itemModuleBinding.moduleDescription.text = module.description
                            module.selectedPeripherals.clear()
                        }



                    } catch (e: Exception) {
                        // Fehlerbehandlung (z.B. Timeout oder falsche IP)
                        Toast.makeText(this@DetailActivity, "Failed to fetch sensors from client!", Toast.LENGTH_SHORT).show()
                        Log.e("Error", "Failed to fetch sensors: ${e.message}")
                    }
                }
            }
            //endregion
        }
    }
}

