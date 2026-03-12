package com.example.hostossi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.ActivityMainBinding
import com.example.hostossi.databinding.FragmentProjectViewBinding
import com.example.hostossi.databinding.ItemProjectBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.sequences.forEach


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



        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

//set up Bottom navigation view
        val bottomNavigationView: BottomNavigationView = binding.bottomNavigationView
//set up Fragments
        val settingsFragment = SettingsFragment()
        val projectViewFragment = ProjectViewFragment()
        val nfcFragment = NFCTool()
        val webUIFragment = WebUI()
        val dashboardFragment = ClientDashboard()
//get settings
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val name = sharedPreferences.getString("deviceMode", "")
//database functionality
        database = (this.application as MyApplication).dataBase
        projectDao = database.projectDao()
        moduleDao = database.moduleDao()
//select fragment functionality
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
}

