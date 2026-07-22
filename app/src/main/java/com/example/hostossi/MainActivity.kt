package com.example.hostossi

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.ActivityMainBinding
import com.example.hostossi.databinding.FragmentProjectViewBinding
import com.example.hostossi.databinding.ItemProjectBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectBinding: ItemProjectBinding
    private lateinit var projectViewBinding: FragmentProjectViewBinding
    private lateinit var projectDao: ProjectDao
    private lateinit var moduleDao: ModuleDao
    private lateinit var database: AppDatabase


    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val themeValue = sharedPreferences.getString("theme_mode", "system")
        val name = sharedPreferences.getString("deviceMode", "host")
        val mode = when (themeValue) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        projectBinding = ItemProjectBinding.inflate(layoutInflater)
        projectViewBinding = FragmentProjectViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = (this.application as MyApplication).dataBase
        if (name == "host") {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val projectsWithModules = database.projectDao().getAllWithModulesOnce()
                    val fullProjects = projectsWithModules.map { pwm ->
                        val p = pwm.project.copy()
                        p.moduleList = pwm.modules.sortedBy { it.module.displayOrder }.map { mwd ->
                            val m = mwd.module.copy()
                            m.deviceList = mwd.devices.toMutableList()
                            m
                        }.toMutableList()
                        p
                    }

                    val stillHost = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                        .getString("deviceMode", "host") == "host"
                    if (!stillHost) {
                        Log.d("MainActivity", "Discarded Room projects after leaving Host mode")
                        return@launch
                    }
                    ProjectManager.replaceProjects(fullProjects)
                    Log.d("MainActivity", "Pre-loaded ${fullProjects.size} host projects")
                    KtorServer.syncProjectsToClient(this@MainActivity)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to pre-load projects", e)
                }
            }
        } else {
            Log.d("MainActivity", "$name mode: skipped local Room project preload")
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // set up Bottom navigation view
        val bottomNavigationView: BottomNavigationView = binding.bottomNavigationView
        // set up Fragments
        val settingsFragment = SettingsFragment()
        val projectViewFragment = ProjectViewFragment()
        val webUIFragment = WebUI()
        val dashboardFragment = ClientDashboard()

        // database functionality
        projectDao = database.projectDao()
        moduleDao = database.moduleDao()

        // select fragment functionality
        bottomNavigationView.setOnNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.projects -> setCurrentFragment(projectViewFragment)
                R.id.settings -> setCurrentFragment(settingsFragment)
                R.id.webui -> setCurrentFragment(webUIFragment)
                R.id.clientDashboard -> setCurrentFragment(dashboardFragment)
            }
            true
        }

        when (name) {
            "client" -> {
                bottomNavigationView.menu.findItem(R.id.clientDashboard).isVisible = true
                bottomNavigationView.menu.findItem(R.id.projects).isVisible = false

                val controlPanelMode = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                configureControlPanelMode(controlPanelMode)
                if (savedInstanceState == null || controlPanelMode) {
                    bottomNavigationView.selectedItemId = if (controlPanelMode) R.id.webui else R.id.clientDashboard
                    if (controlPanelMode) setCurrentFragment(webUIFragment)
                }
                KtorServer.startServer(this)
            }

            "viewer" -> {
                bottomNavigationView.menu.findItem(R.id.clientDashboard).isVisible = false
                bottomNavigationView.menu.findItem(R.id.projects).isVisible = false
                bottomNavigationView.menu.findItem(R.id.webui).title = "Viewer"

                val viewingPanelMode = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                configureControlPanelMode(viewingPanelMode)
                if (savedInstanceState == null || viewingPanelMode) {
                    bottomNavigationView.selectedItemId = R.id.webui
                    if (viewingPanelMode) setCurrentFragment(webUIFragment)
                }
                KtorServer.stopServer()
            }

            else -> {
                bottomNavigationView.menu.findItem(R.id.clientDashboard).isVisible = false
                bottomNavigationView.menu.findItem(R.id.projects).isVisible = true
                if (savedInstanceState == null) {
                    bottomNavigationView.selectedItemId = R.id.projects
                }
                KtorServer.stopServer()
                Log.d("test", "i am now a host")
            }
        }

    }

    private fun configureControlPanelMode(enabled: Boolean) {
        binding.bottomNavigationView.isVisible = !enabled
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun setCurrentFragment(fragment: Fragment) =
        supportFragmentManager.beginTransaction().apply {
            setCustomAnimations(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
            replace(R.id.flFragment, fragment)
            commit()
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
