package com.example.hostossi

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        projectBinding = ItemProjectBinding.inflate(layoutInflater)
        projectViewBinding = FragmentProjectViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-load projects into ProjectManager AND trigger a sync to the client
        // to ensure other fragments (like WebUI) have access to data immediately on startup,
        // without needing to visit ProjectViewFragment first.
        database = (this.application as MyApplication).dataBase
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // We use getAllWithModulesOnce because the dashboard needs modules
                val projectsWithModules = database.projectDao().getAllWithModulesOnce()
                ProjectManager.projectList.clear()
                
                val fullProjects = projectsWithModules.map { pwm ->
                    val p = pwm.project.copy()
                    p.moduleList = pwm.modules.toMutableList()
                    p
                }
                
                ProjectManager.projectList.addAll(fullProjects)
                Log.d("MainActivity", "Pre-loaded ${fullProjects.size} projects with modules into ProjectManager")
                
                // IMPORTANT: This triggers the background sync that the dashboard relies on
                KtorServer.syncProjectsToClient(this@MainActivity)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to pre-load projects", e)
            }
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
        val nfcFragment = NFCTool()
        val webUIFragment = WebUI()
        val dashboardFragment = ClientDashboard()

        // get settings
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val name = sharedPreferences.getString("deviceMode", "")

        // database functionality
        projectDao = database.projectDao()
        moduleDao = database.moduleDao()

        // select fragment functionality
        bottomNavigationView.setOnNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.projects -> setCurrentFragment(projectViewFragment)
                R.id.settings -> setCurrentFragment(settingsFragment)
                R.id.nfc -> setCurrentFragment(nfcFragment)
                R.id.webui -> setCurrentFragment(webUIFragment)
                R.id.clientDashboard -> setCurrentFragment(dashboardFragment)
            }
            true
        }
        Log.d("test", "this happened on install")

        if(name == "client"){
            bottomNavigationView.menu.findItem(R.id.clientDashboard).isVisible = true
            bottomNavigationView.menu.findItem(R.id.projects).isVisible = false
            bottomNavigationView.menu.findItem(R.id.clientDashboard).isChecked = true
            setCurrentFragment(dashboardFragment)

            KtorServer.startServer(this)
        }
        else{
            bottomNavigationView.menu.findItem(R.id.clientDashboard).isVisible = false
            bottomNavigationView.menu.findItem(R.id.projects).isVisible = true
            setCurrentFragment(projectViewFragment)

            // When in Host mode, we don't need the local dashboard server running
            KtorServer.stopServer()

            Log.d("test", "i am now a host")
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
