package com.thomaspatrick.obsidiantasks.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.thomaspatrick.obsidiantasks.ObsidianTasksWidgetProvider
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.TaskRepository
import com.thomaspatrick.obsidiantasks.databinding.ActivityQuickAddBinding

class QuickAddActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickAddBinding
    private lateinit var prefs: AppPreferences

    private var mode = MODE_ADD
    private var targetLineIndex = -1
    private var originalLine: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickAddBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ADD
        targetLineIndex = intent.getIntExtra(ObsidianTasksWidgetProvider.EXTRA_LINE_INDEX, -1)
        originalLine = intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_ORIGINAL_LINE)
        val initialText = intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_TASK_TEXT) ?: ""

        setupUI(initialText)
        setupListeners()
        requestKeyboard()
    }

    private fun setupUI(initialText: String) {
        // Tapping on the translucent scrim outside the dialog dismisses it
        binding.dialogScrimRoot.setOnClickListener {
            dismissAndFinish()
        }

        if (mode == MODE_EDIT) {
            binding.tvDialogTitle.text = "Edit Task"
            binding.btnConfirmAdd.text = "Save"
            binding.btnDeleteTask.visibility = View.VISIBLE
            binding.etNewTask.setText(initialText)
            binding.etNewTask.setSelection(initialText.length)
        } else {
            binding.tvDialogTitle.text = "New Task"
            binding.btnConfirmAdd.text = "Add Task"
            binding.btnDeleteTask.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnCancelAdd.setOnClickListener {
            dismissAndFinish()
        }

        binding.btnDeleteTask.setOnClickListener {
            deleteTaskAndFinish()
        }

        binding.btnConfirmAdd.setOnClickListener {
            saveTaskAndFinish()
        }

        binding.etNewTask.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveTaskAndFinish()
                true
            } else {
                false
            }
        }
    }

    private fun requestKeyboard() {
        binding.etNewTask.requestFocus()
        binding.etNewTask.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etNewTask, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun saveTaskAndFinish() {
        val taskText = binding.etNewTask.text.toString().trim()
        if (taskText.isEmpty()) {
            Toast.makeText(this, "Please enter a task description", Toast.LENGTH_SHORT).show()
            return
        }

        if (prefs.fileUriString.isNullOrEmpty() && !TaskRepository.isRemote(prefs)) {
            Toast.makeText(this, "No Obsidian note configured.", Toast.LENGTH_LONG).show()
            dismissAndFinish()
            return
        }

        val success = if (mode == MODE_EDIT) {
            TaskRepository.rename(this, targetLineIndex, originalLine, taskText)
        } else {
            TaskRepository.add(this, taskText)
        }

        if (success) {
            val message = if (mode == MODE_EDIT) "Task updated" else "Task added"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            refreshWidgetAndSync()
        } else {
            Toast.makeText(this, "Note changed meanwhile. Refreshed, try again.", Toast.LENGTH_SHORT).show()
            TaskRepository.refreshWidgets(this)
        }

        dismissAndFinish()
    }

    private fun deleteTaskAndFinish() {
        val success = TaskRepository.delete(this, targetLineIndex, originalLine)
        if (success) {
            Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show()
            refreshWidgetAndSync()
        } else {
            Toast.makeText(this, "Note changed meanwhile. Refreshed, try again.", Toast.LENGTH_SHORT).show()
            TaskRepository.refreshWidgets(this)
        }
        dismissAndFinish()
    }

    private fun dismissAndFinish() {
        finishAndRemoveTask()
    }

    override fun finish() {
        super.finish()
        finishAndRemoveTask()
    }

    private fun refreshWidgetAndSync() {
        // Upload (if Nextcloud is on) is already queued by TaskRepository.
        TaskRepository.refreshWidgets(this)
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_ADD = "mode_add"
        const val MODE_EDIT = "mode_edit"
    }
}
