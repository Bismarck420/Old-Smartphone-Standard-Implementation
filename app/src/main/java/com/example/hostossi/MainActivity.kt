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
            KtorServer.stopClient()



        }
        else{
            bottomNavigationView.menu.findItem(R.id.clientDashboard).isVisible = false
            bottomNavigationView.menu.findItem(R.id.projects).isVisible = true
            setCurrentFragment(projectViewFragment)

            KtorServer.stopServer()
            KtorServer.startClient(this)




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



    fun updateUIfromDB(projects: List<Project>) {
        projectViewBinding.projectContainer.removeAllViews()

        for (project in projects) {
            Log.d("updateUIfromDB", project.toString())

            val itemProjectBinding = ItemProjectBinding.inflate(layoutInflater)

            itemProjectBinding.projectTitle.text = project.name
            itemProjectBinding.projectDescription.text = project.description
            if (project.isSelectedProject) {
                itemProjectBinding.idProjectSelected.visibility = View.VISIBLE
                itemProjectBinding.projectCard.setCardBackgroundColor(resources.getColor(R.color.selected_back_color))
                ProjectManager.selectedProject = project

                Log.d("test", "i got here")
            }


            itemProjectBinding.projectCard.setOnClickListener {
                val visible = itemProjectBinding.projectDescription.visibility

                if (visible == View.VISIBLE) {
                    itemProjectBinding.projectDescription.visibility = View.GONE
                    itemProjectBinding.openProjectButton.visibility = View.GONE
                } else {
                    itemProjectBinding.projectDescription.visibility = View.VISIBLE
                    itemProjectBinding.openProjectButton.visibility = View.VISIBLE
                }
            }

            itemProjectBinding.openProjectButton.setOnClickListener {
                val intent = Intent(this, DetailActivity::class.java)

                intent.putExtra("EXTRA_PROJECT_ID", project.id)
                startActivity(intent)
            }


            itemProjectBinding.verticalMenu.setOnClickListener {
                val popupMenu = PopupMenu(this, itemProjectBinding.verticalMenu)
                popupMenu.menu.add("Delete")
                popupMenu.menu.add("Edit")
                popupMenu.menu.add("Select Project")
                popupMenu.menu.add("Deselect Project")

                if (ProjectManager.selectedProject == project) {
                    popupMenu.menu.children.forEach {
                        if (it.title == "Select Project") {
                            it.setVisible(false)
                        }
                    }
                } else {
                    popupMenu.menu.children.forEach {
                        if (it.title == "Deselect Project") {
                            it.setVisible(false)
                        }
                    }
                }

                popupMenu.setOnMenuItemClickListener { item ->
                    var menuText: String = item.title as String

                    if (menuText == "Delete") {
                        projectViewBinding.projectContainer.removeView(itemProjectBinding.root)
                        ProjectManager.projectList.remove(project)
                        lifecycleScope.launch(Dispatchers.IO) {
                            projectDao.delete(project)
                        }
                        true
                    } else if (menuText == "Edit") {
                        // TODO: make title & description editable
                        true
                    } else if (menuText == "Select Project") {
                        itemProjectBinding.idProjectSelected.visibility = View.VISIBLE
                        itemProjectBinding.projectCard.setCardBackgroundColor(resources.getColor(R.color.selected_back_color))

                        lifecycleScope.launch(Dispatchers.IO) {
                            for (project in ProjectManager.projectList) {
                                project.isSelectedProject = false
                                projectDao.updateProject(project)

                                Log.d("test", project.isSelectedProject.toString())
                            }
                        }

                        project.isSelectedProject = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            projectDao.updateProject(project)
                        }


                        ProjectManager.selectedProject = project

                        popupMenu.menu.children.forEach {
                            Log.d("test", it.toString())
                            if (it.title == "Select Project") {
                                it.setVisible(false)
                            }
                            if (it.title == "Deselect Project") {
                                it.setVisible(true)
                            }
                        }
                        KtorServer.startClient(this)

                        true
                    } else if (menuText == "Deselect Project") {
                        itemProjectBinding.idProjectSelected.visibility = View.GONE
                        itemProjectBinding.projectCard.setCardBackgroundColor(itemProjectBinding.projectCard.cardBackgroundColor.defaultColor)
                        project.isSelectedProject = false
                        lifecycleScope.launch(Dispatchers.IO) { projectDao.updateProject(project) }

                        ProjectManager.selectedProject = Project()

                        popupMenu.menu.children.forEach {
                            Log.d("test", it.toString())
                            if (it.title == "Select Project") {
                                it.setVisible(true)
                            }
                            if (it.title == "Deselect Project") {
                                it.setVisible(false)
                            }
                        }

                        true
                    } else {
                        false
                    }

                }

                popupMenu.show()
            }

            projectViewBinding.projectContainer.addView(itemProjectBinding.root)
        }
    }
}

