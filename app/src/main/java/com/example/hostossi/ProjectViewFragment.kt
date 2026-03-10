package com.example.hostossi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.removeItemAt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.FragmentProjectViewBinding
import com.example.hostossi.databinding.ItemProjectBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.sequences.forEach

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [ProjectViewFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class ProjectViewFragment : Fragment(R.layout.fragment_project_view) {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null
    private var _binding: FragmentProjectViewBinding? = null

    // 2. Eine "Backing Property", um nicht überall '!!' oder '?' nutzen zu müssen
    private val binding get() = _binding!!
    private lateinit var projectDao: ProjectDao
    private lateinit var moduleDao: ModuleDao
    private lateinit var database: AppDatabase


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }


    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentProjectViewBinding.bind(view)
        binding.projectContainer.removeAllViews()

        database = (requireActivity().application as MyApplication).dataBase
        projectDao = database.projectDao()
        moduleDao = database.moduleDao()

        Log.d("test", "updated ui")
        binding.projectContainer.removeAllViews()
        updateUIfromDB()

        binding.btnAddnewProjectButton.setOnClickListener { addNewProject() }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val name = sharedPreferences.getString("deviceMode", "")

        val deviceModeCard: MaterialCardView = requireActivity().findViewById(R.id.idDeviceModeCard)
        val deviceMode: TextView = requireActivity().findViewById(R.id.deviceMode)
        if (name == "client") {
            Log.d("test", "entered client if")
            deviceModeCard.strokeColor = resources.getColor(R.color.client_color)
            deviceModeCard.setCardBackgroundColor(resources.getColor(R.color.client_color))
            deviceMode.text = "Client"

        }
        else {
            deviceModeCard.strokeColor = resources.getColor(R.color.host_color)
            deviceModeCard.setCardBackgroundColor(resources.getColor(R.color.host_color))
            deviceMode.text = "Host"

        }

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_project_view, container, false)
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment projectViewFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            ProjectViewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 4. WICHTIG: Speicherleck verhindern!
        // Binding null setzen, wenn die View weg ist.
        _binding = null
    }

    fun addNewProject() {
        val itemProjectBinding = ItemProjectBinding.inflate(LayoutInflater.from(requireContext()), binding.projectContainer, false)
        itemProjectBinding.projectCard.id = View.generateViewId()

        val newProject = Project(name="new Project")
        newProject.name = newProject.name + " " + itemProjectBinding.projectCard.id.toString()
        itemProjectBinding.projectTitle.text = newProject.name
        newProject.description = "Generic Project"
        itemProjectBinding.projectDescription.text = newProject.description

        ProjectManager.projectList.add(newProject)
        lifecycleScope.launch (Dispatchers.IO){
            projectDao.insertProject(newProject)
            Log.d("test", "addNewProject method")
        }
        setOnClickListeners(itemProjectBinding, newProject)

        binding.projectContainer.addView(itemProjectBinding.root)
    }

    fun updateUIfromDB() {
        ProjectManager.projectList.clear()
        Log.d("test", "entered updateUIfromDB")
        lifecycleScope.launch(Dispatchers.IO) {
            projectDao.getAll().collect { projects ->
                Log.d("test", "This is the total amount of projects: " + projects.size.toString())
                withContext(Dispatchers.Main){
                    binding.projectContainer.removeAllViews()
                }
                for (myProject in projects) {
                    Log.d("test", "this project is called " + myProject.name)
                    ProjectManager.projectList.add(myProject)

                    val itemProjectBinding = ItemProjectBinding.inflate(layoutInflater)

                    itemProjectBinding.projectTitle.text = myProject.name
                    itemProjectBinding.projectDescription.text = myProject.description
//                    if (myProject.isSelectedProject) {
//                        itemProjectBinding.idProjectSelected.visibility = View.VISIBLE
//                        itemProjectBinding.projectCard.setCardBackgroundColor(resources.getColor(R.color.selected_back_color))
//                        ProjectManager.selectedProject = myProject
//                    }
//                    else if (myProject.isSelectedProject == false){
//                        itemProjectBinding.idProjectSelected.visibility = View.GONE
//                        itemProjectBinding.projectCard.setCardBackgroundColor(itemProjectBinding.projectCard.cardBackgroundColor.defaultColor)
//                    }
//
                      setOnClickListeners(itemProjectBinding, myProject)
//
                    withContext(Dispatchers.Main){
                        binding.projectContainer.addView(itemProjectBinding.root)
                    }
                    Log.d("test", "added project to view")

                }
            }

        }
    }

    fun setOnClickListeners(itemProjectBinding: ItemProjectBinding, project: Project){
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
            val intent = Intent(requireActivity(), DetailActivity::class.java)
            intent.putExtra("EXTRA_PROJECT_ID", project.id)
            startActivity(intent)
        }


        itemProjectBinding.verticalMenu.setOnClickListener {
            val popupMenu = PopupMenu(requireActivity(), itemProjectBinding.verticalMenu)
            popupMenu.menu.add("Delete")
            popupMenu.menu.add("Edit")
            popupMenu.menu.add("Select Project")
            popupMenu.menu.add("Deselect Project")

            if(ProjectManager.selectedProject == project){
                popupMenu.menu.children.forEach {
                    if(it.title == "Select Project") {
                        it.setVisible(false)
                    }
                }
            }
            else{
                popupMenu.menu.children.forEach {
                    if(it.title == "Deselect Project") {
                        it.setVisible(false)
                    }
                }
            }

            popupMenu.setOnMenuItemClickListener { item ->
                var menuText: String = item.title as String

                if (menuText == "Delete") {
                    Log.d("test", "delete in updateui clicked")

                    binding.projectContainer.removeView(itemProjectBinding.root)
                    ProjectManager.projectList.remove(project)
                    lifecycleScope.launch(Dispatchers.IO) {
                        projectDao.delete(project)
                    }
                    true
                } else if (menuText == "Edit") {
                    // TODO: make title & description editable
                    true
                } else if (menuText == "Select Project") {
                    Log.d("test", "entered Select Project updateUIfromDB")

                    itemProjectBinding.idProjectSelected.visibility = View.VISIBLE
                    itemProjectBinding.projectCard.setCardBackgroundColor(resources.getColor(R.color.selected_back_color))

                    ProjectManager.selectedProject = project

                    lifecycleScope.launch(Dispatchers.IO){
                        projectDao.getAll().collect { projects ->
                            for(selectedProject in projects){
                                if(selectedProject.isSelectedProject){
                                    selectedProject.isSelectedProject = false
                                    projectDao.updateProject(selectedProject)

                                }
                            }
                        }
                        project.isSelectedProject = true
                        Log.d("test", project.toString())
                        projectDao.updateProject(project)
                    }

                    popupMenu.menu.children.forEach {
                        if(it.title == "Select Project") {
                            it.setVisible(false)
                        }
                        if(it.title == "Deselect Project"){
                            it.setVisible(true)
                        }
                    }
                    KtorServer.startClient(requireActivity())
                    true
                }
                else if(menuText =="Deselect Project"){
                    itemProjectBinding.idProjectSelected.visibility = View.GONE
                    itemProjectBinding.projectCard.setCardBackgroundColor(itemProjectBinding.projectCard.cardBackgroundColor.defaultColor)
                    project.isSelectedProject = false
                    lifecycleScope.launch (Dispatchers.IO){ projectDao.updateProject(project) }

                    ProjectManager.selectedProject = Project()

                    popupMenu.menu.children.forEach {
                        if(it.title == "Select Project") {
                            it.setVisible(false)
                        }
                        if(it.title == "Deselect Project"){
                            it.setVisible(true)
                        }
                    }
                    true
                }
                else {
                    false
                }

            }

            popupMenu.show()
        }
    }

    fun selectProject(selectedProject : Project, itemProjectBinding: ItemProjectBinding, popupMenu: PopupMenu){

    }

    fun deselectProject(deselectedProject : Project, itemProjectBinding: ItemProjectBinding, popupMenu: PopupMenu){

    }
}
