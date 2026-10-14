package com.glassvault.widget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.glassvault.widget.LiquidTodoWidgetProvider
import com.glassvault.widget.R
import com.glassvault.widget.data.AppPreferences
import com.glassvault.widget.data.MarkdownTaskManager
import com.glassvault.widget.databinding.ActivityQuickAddBinding
import com.glassvault.widget.sync.NextcloudWebDavSyncWorker

class QuickAddActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickAddBinding
    private lateinit var prefs: AppPreferences

    private var mode = MODE_ADD
    private var targetLineIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickAddBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ADD
        targetLineIndex = intent.getIntExtra(LiquidTodoWidgetProvider.EXTRA_LINE_INDEX, -1)
        val initialText = intent.getStringExtra(LiquidTodoWidgetProvider.EXTRA_TASK_TEXT) ?: ""

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

        val uriString = prefs.fileUriString
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, "No Obsidian note configured.", Toast.LENGTH_LONG).show()
            dismissAndFinish()
            return
        }

        val uri = Uri.parse(uriString)
        val success = if (mode == MODE_EDIT) {
            MarkdownTaskManager.updateTaskTitle(this, uri, targetLineIndex, taskText)
        } else {
            MarkdownTaskManager.addTask(this, uri, taskText)
        }

        if (success) {
            val message = if (mode == MODE_EDIT) "Task updated" else "Task added"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            refreshWidgetAndSync()
        } else {
            Toast.makeText(this, "Failed to update note", Toast.LENGTH_SHORT).show()
        }

        dismissAndFinish()
    }

    private fun deleteTaskAndFinish() {
        val uriString = prefs.fileUriString ?: return
        val uri = Uri.parse(uriString)

        val success = MarkdownTaskManager.deleteTask(this, uri, targetLineIndex)
        if (success) {
            Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show()
            refreshWidgetAndSync()
        } else {
            Toast.makeText(this, "Failed to delete task", Toast.LENGTH_SHORT).show()
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
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, LiquidTodoWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
        for (id in widgetIds) {
            LiquidTodoWidgetProvider.updateAppWidget(this, appWidgetManager, id)
        }

        if (prefs.isCloudSyncEnabled) {
            val syncRequest = OneTimeWorkRequestBuilder<NextcloudWebDavSyncWorker>()
                .setInputData(workDataOf(NextcloudWebDavSyncWorker.KEY_SYNC_MODE to NextcloudWebDavSyncWorker.MODE_UPLOAD))
                .build()

            WorkManager.getInstance(this).enqueueUniqueWork(
                "NEXTCLOUD_PUSH_ON_EDIT",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        }
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_ADD = "mode_add"
        const val MODE_EDIT = "mode_edit"
    }
}
