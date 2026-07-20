package com.example.hostossi

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hostossi.databinding.FragmentProjectViewBinding
import com.example.hostossi.databinding.ItemProjectBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

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
    private lateinit var projectAdapter: ProjectAdapter
    private lateinit var projectTouchHelper: ItemTouchHelper
    private var dragStartPosition = RecyclerView.NO_POSITION


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

        database = (requireActivity().application as MyApplication).dataBase
        projectDao = database.projectDao()
        moduleDao = database.moduleDao()

        setupProjectList()
        Log.d("test", "updated ui")
        updateUIfromDB()

        binding.btnAddnewProjectButton.setOnClickListener { addNewProject() }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val name = sharedPreferences.getString("deviceMode", "")

        val deviceModeCard: MaterialCardView = requireActivity().findViewById(R.id.idDeviceModeCard)
        val deviceMode: TextView = requireActivity().findViewById(R.id.deviceMode)
        val context = requireContext()
        if (name == "client") {
            Log.d("test", "entered client if")
            deviceModeCard.strokeColor = ContextCompat.getColor(context, R.color.client_color)
            deviceModeCard.setCardBackgroundColor(ContextCompat.getColor(context, R.color.client_color))
            deviceMode.text = "Client"

        }
        else {
            deviceModeCard.strokeColor = ContextCompat.getColor(context, R.color.host_color)
            deviceModeCard.setCardBackgroundColor(ContextCompat.getColor(context, R.color.host_color))
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
        val newProject = Project(
            name = "New Project ${projectAdapter.itemCount + 1}",
            description = "Generic Project",
            displayOrder = projectAdapter.itemCount
        )

        ProjectManager.projectList.add(newProject)
        lifecycleScope.launch (Dispatchers.IO){
            try {
                projectDao.insertProject(newProject)
                Log.d("test", "addNewProject method")
                KtorServer.syncProjectsToClient(requireContext())
            } catch (e: Exception) {
                Log.e("ProjectView", "Failed to add new project", e)
                withContext(Dispatchers.Main) {
                    SnackbarUtils.showModernSnackbar(binding.root, "Error saving project", anchorView = binding.btnAddnewProjectButton)
                }
            }
        }
    }

    fun updateUIfromDB() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                projectDao.getAllWithModules().collect { projectsWithModules ->
                    Log.d("test", "Collecting projects: " + projectsWithModules.size.toString())
                    
                    val fullProjects = projectsWithModules.map { pwm ->
                        val p = pwm.project.copy()
                        p.moduleList = pwm.modules.map { mwd ->
                            val m = mwd.module.copy()
                            m.deviceList = mwd.devices.toMutableList()
                            m
                        }.toMutableList()
                        p
                    }

                    // Atomic update of global list
                    ProjectManager.projectList.clear()
                    ProjectManager.projectList.addAll(fullProjects)

                    withContext(Dispatchers.Main){
                        projectAdapter.submit(fullProjects)
                    }
                    KtorServer.syncProjectsToClient(requireContext())
                }
            } catch (e: Exception) {
                Log.e("ProjectView", "Failed to update UI from DB", e)
                withContext(Dispatchers.Main) {
                    SnackbarUtils.showModernSnackbar(binding.root, "Database error", anchorView = binding.btnAddnewProjectButton)
                }
            }
        }
    }

    fun setOnClickListeners(itemProjectBinding: ItemProjectBinding, project: Project){
        var expandedBeforeEditing = false

        fun hideKeyboard() {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(itemProjectBinding.root.windowToken, 0)
        }

        fun setEditMode(enabled: Boolean) {
            if (enabled) {
                expandedBeforeEditing = itemProjectBinding.projectDescription.isVisible
                itemProjectBinding.editableProjectTitle.setText(project.name)
                itemProjectBinding.editableProjectDescription.setText(project.description)
            }

            itemProjectBinding.projectTitle.isVisible = !enabled
            itemProjectBinding.projectDescription.isVisible = !enabled && expandedBeforeEditing
            itemProjectBinding.moduleSummary.isVisible = !enabled && expandedBeforeEditing
            itemProjectBinding.openProjectButton.isVisible = !enabled && expandedBeforeEditing
            itemProjectBinding.verticalMenu.isVisible = !enabled
            itemProjectBinding.editableProjectTitle.isVisible = enabled
            itemProjectBinding.projectDescriptionInput.isVisible = enabled
            itemProjectBinding.projectEditActions.isVisible = enabled

            if (enabled) {
                itemProjectBinding.editableProjectTitle.requestFocus()
                itemProjectBinding.editableProjectTitle.post {
                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(itemProjectBinding.editableProjectTitle, InputMethodManager.SHOW_IMPLICIT)
                    itemProjectBinding.editableProjectTitle.setSelection(itemProjectBinding.editableProjectTitle.text.length)
                }
            } else {
                hideKeyboard()
            }
        }

        fun saveProject() {
            val newName = itemProjectBinding.editableProjectTitle.text.toString().trim()
            if (newName.isBlank()) {
                itemProjectBinding.editableProjectTitle.error = "Project name is required"
                itemProjectBinding.editableProjectTitle.requestFocus()
                return
            }

            val newDescription = itemProjectBinding.editableProjectDescription.text?.toString()?.trim().orEmpty()
            project.name = newName
            project.description = newDescription
            itemProjectBinding.projectTitle.text = newName
            itemProjectBinding.projectDescription.text = newDescription
            setEditMode(false)

            lifecycleScope.launch(Dispatchers.IO) {
                projectDao.updateProject(project)
                KtorServer.syncProjectsToClient(requireContext())
            }
            SnackbarUtils.showModernSnackbar(
                binding.root,
                "Project updated",
                anchorView = binding.btnAddnewProjectButton
            )
        }

        itemProjectBinding.projectCard.setOnClickListener {
            if (itemProjectBinding.editableProjectTitle.isVisible) return@setOnClickListener
            val detailsVisible = itemProjectBinding.projectDescription.isVisible
            itemProjectBinding.projectDescription.isVisible = !detailsVisible
            itemProjectBinding.moduleSummary.isVisible = !detailsVisible
            itemProjectBinding.openProjectButton.isVisible = !detailsVisible
        }

        itemProjectBinding.openProjectButton.setOnClickListener {
            val intent = Intent(requireActivity(), DetailActivity::class.java)
            intent.putExtra("EXTRA_PROJECT_ID", project.id)
            startActivity(intent)
        }

        itemProjectBinding.editableProjectTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                itemProjectBinding.editableProjectDescription.requestFocus()
                true
            } else false
        }

        itemProjectBinding.editableProjectDescription.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveProject()
                true
            } else false
        }

        itemProjectBinding.cancelProjectEdit.setOnClickListener {
            setEditMode(false)
        }

        itemProjectBinding.saveProjectEdit.setOnClickListener {
            saveProject()
        }

        itemProjectBinding.verticalMenu.setOnClickListener {
            val popupMenu = PopupMenu(requireActivity(), itemProjectBinding.verticalMenu)
            popupMenu.menu.add("Edit")
            popupMenu.menu.add("Delete")

            popupMenu.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Delete" -> {
                        ProjectManager.projectList.remove(project)
                        lifecycleScope.launch(Dispatchers.IO) {
                            projectDao.delete(project)
                        }
                        true
                    }
                    "Edit" -> {
                        setEditMode(true)
                        true
                    }
                    else -> false
                }
            }

            popupMenu.show()
        }
    }

    private fun setupProjectList() {
        projectAdapter = ProjectAdapter()
        binding.projectContainer.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = projectAdapter
            setHasFixedSize(false)
            itemAnimator?.moveDuration = 220
            itemAnimator?.changeDuration = 160
        }

        projectTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
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
                projectAdapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder is ProjectViewHolder) {
                    dragStartPosition = viewHolder.bindingAdapterPosition
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.binding.projectCard.strokeWidth = resources.getDimensionPixelSize(R.dimen.drag_target_stroke)
                    viewHolder.itemView.animate()
                        .scaleX(1.035f).scaleY(1.035f).alpha(0.92f)
                        .translationZ(resources.getDimension(R.dimen.drag_elevation))
                        .setDuration(150).start()
                    SnackbarUtils.showModernSnackbar(
                        binding.root,
                        "Move the project, then release to save",
                        anchorView = binding.btnAddnewProjectButton
                    )
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                (viewHolder as? ProjectViewHolder)?.binding?.projectCard?.strokeWidth = 0
                viewHolder.itemView.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f).translationZ(0f)
                    .setDuration(180).start()
                val endPosition = viewHolder.bindingAdapterPosition
                if (dragStartPosition != RecyclerView.NO_POSITION &&
                    endPosition != RecyclerView.NO_POSITION &&
                    dragStartPosition != endPosition
                ) {
                    persistProjectOrder()
                }
                dragStartPosition = RecyclerView.NO_POSITION
            }
        }).also { it.attachToRecyclerView(binding.projectContainer) }
    }

    private fun persistProjectOrder() {
        val orderedProjects = projectAdapter.orderedProjects()
            .onEachIndexed { index, project -> project.displayOrder = index }
        ProjectManager.projectList.clear()
        ProjectManager.projectList.addAll(orderedProjects)
        lifecycleScope.launch(Dispatchers.IO) {
            projectDao.updateProjectOrder(orderedProjects)
            KtorServer.syncProjectsToClient(requireContext())
        }
        SnackbarUtils.showModernSnackbar(
            binding.root,
            "Project order saved",
            anchorView = binding.btnAddnewProjectButton
        )
    }

    private fun renderModuleBadges(itemBinding: ItemProjectBinding, project: Project) {
        val modules = project.moduleList.sortedBy { it.displayOrder }
        itemBinding.moduleBadges.removeAllViews()
        itemBinding.moduleCountLabel.text = when (modules.size) {
            0 -> "NO MODULES YET"
            1 -> "1 MODULE"
            else -> "${modules.size} MODULES"
        }
        itemBinding.moduleBadges.isVisible = modules.isNotEmpty()

        val visibleModules = modules.take(4)
        visibleModules.forEach { module ->
            val isSwitch = module.moduleType.equals("Switch", ignoreCase = true)
            val background = if (isSwitch) R.color.switch_badge_background else R.color.generic_badge_background
            val foreground = if (isSwitch) R.color.switch_badge_text else R.color.generic_badge_text
            itemBinding.moduleBadges.addView(createModuleChip(
                module.moduleTitle.ifBlank { module.moduleType.ifBlank { "Untitled module" } },
                background,
                foreground
            ))
        }

        val hiddenCount = modules.size - visibleModules.size
        if (hiddenCount > 0) {
            itemBinding.moduleBadges.addView(createModuleChip(
                "+$hiddenCount more",
                R.color.generic_badge_background,
                R.color.generic_badge_text
            ))
        }
    }

    private fun createModuleChip(label: String, backgroundColor: Int, textColor: Int): Chip {
        val density = resources.displayMetrics.density
        return Chip(requireContext()).apply {
            text = label
            textSize = 11f
            isCheckable = false
            isClickable = false
            isFocusable = false
            isSingleLine = true
            maxWidth = (180 * density).toInt()
            chipMinHeight = 28 * density
            chipCornerRadius = 14 * density
            chipStrokeWidth = 0f
            chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), backgroundColor))
            setTextColor(ContextCompat.getColor(requireContext(), textColor))
            setEnsureMinTouchTargetSize(false)
        }
    }

    private inner class ProjectViewHolder(val binding: ItemProjectBinding) :
        RecyclerView.ViewHolder(binding.root)

    private inner class ProjectAdapter : RecyclerView.Adapter<ProjectViewHolder>() {
        private val items = mutableListOf<Project>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder =
            ProjectViewHolder(ItemProjectBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
            val project = items[position]
            holder.binding.apply {
                projectTitle.text = project.name
                projectDescription.text = project.description
                renderModuleBadges(this, project)
                projectCard.strokeWidth = 0
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
                setOnClickListeners(this, project)
                projectCard.setOnLongClickListener {
                    projectTouchHelper.startDrag(holder)
                    true
                }
            }
        }

        override fun getItemCount() = items.size

        fun submit(projects: List<Project>) {
            val next = projects.sortedBy { it.displayOrder }
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

        fun orderedProjects(): List<Project> = items.toList()
    }
}
