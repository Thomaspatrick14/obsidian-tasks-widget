package com.thomaspatrick.obsidiantasks.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.thomaspatrick.obsidiantasks.ObsidianTasksWidgetProvider
import com.thomaspatrick.obsidiantasks.R
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.MarkdownTaskManager
import com.thomaspatrick.obsidiantasks.data.TaskRepository
import com.thomaspatrick.obsidiantasks.databinding.ActivityConfigBinding
import com.thomaspatrick.obsidiantasks.sync.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private lateinit var prefs: AppPreferences
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedUri: Uri? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onMarkdownFileSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        // Find widget ID from intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Default result is CANCELED if user backs out
        setResult(Activity.RESULT_CANCELED)

        initExistingPreferences()
        setupListeners()
    }

    private fun initExistingPreferences() {
        val currentUriString = prefs.fileUriString
        if (!currentUriString.isNullOrEmpty()) {
            selectedUri = Uri.parse(currentUriString)
            binding.tvSelectedFile.text = "📄 ${prefs.fileName}"
        }

        binding.switchShowCompleted.isChecked = prefs.showCompleted

        // Nextcloud settings
        binding.switchCloudSync.isChecked = prefs.isCloudSyncEnabled
        binding.layoutCloudForm.visibility = if (prefs.isCloudSyncEnabled) View.VISIBLE else View.GONE

        if (prefs.nextcloudUrl.isNotEmpty()) binding.etNextcloudUrl.setText(prefs.nextcloudUrl)
        if (prefs.nextcloudUsername.isNotEmpty()) binding.etNextcloudUser.setText(prefs.nextcloudUsername)
        if (prefs.nextcloudPassword.isNotEmpty()) binding.etNextcloudPass.setText(prefs.nextcloudPassword)
        if (prefs.nextcloudRemotePath.isNotEmpty()) binding.etNextcloudRemotePath.setText(prefs.nextcloudRemotePath)
    }

    private fun setupListeners() {
        binding.btnPickFile.setOnClickListener {
            // Filter for markdown or text files
            filePickerLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
        }

        binding.switchCloudSync.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutCloudForm.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnTestConnection.setOnClickListener {
            testNextcloudConnection()
        }

        binding.btnSaveConfig.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun onMarkdownFileSelected(uri: Uri) {
        try {
            // Take permanent persistable permission so widget can read/write across reboots
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)

            selectedUri = uri
            val docFile = DocumentFile.fromSingleUri(this, uri)
            val fileName = docFile?.name ?: "Obsidian Note"

            prefs.fileUriString = uri.toString()
            prefs.fileName = fileName

            // Count detected tasks
            val tasks = MarkdownTaskManager.loadTasks(this, uri)
            binding.tvSelectedFile.text = "📄 $fileName (${tasks.size} tasks detected)"

            // Auto-fill remote path suggestion if empty
            if (binding.etNextcloudRemotePath.text.isNullOrEmpty()) {
                binding.etNextcloudRemotePath.setText("/Obsidian/$fileName")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun testNextcloudConnection() {
        val url = binding.etNextcloudUrl.text.toString().trim()
        val user = binding.etNextcloudUser.text.toString().trim()
        val pass = binding.etNextcloudPass.text.toString().trim()
        val path = binding.etNextcloudRemotePath.text.toString().trim()

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty() || path.isEmpty()) {
            Toast.makeText(this, "Please fill in all Nextcloud fields", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvConnectionStatus.visibility = View.VISIBLE
        binding.tvConnectionStatus.text = "Testing connection..."
        binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_cyan))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WebDavClient.testConnection(url, user, pass, path)
            }

            if (result.isSuccess) {
                binding.tvConnectionStatus.text = "✓ " + result.getOrNull()
                binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_green))
            } else {
                binding.tvConnectionStatus.text = "✗ " + result.exceptionOrNull()?.message
                binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_red))
            }
        }
    }

    private fun saveAndFinish() {
        val cloudOn = binding.switchCloudSync.isChecked
        if (!cloudOn && selectedUri == null && prefs.fileUriString == null) {
            Toast.makeText(this, "Please select an Obsidian markdown note first", Toast.LENGTH_LONG).show()
            return
        }

        prefs.showCompleted = binding.switchShowCompleted.isChecked

        val url = binding.etNextcloudUrl.text.toString().trim()
        val user = binding.etNextcloudUser.text.toString().trim()
        val pass = binding.etNextcloudPass.text.toString().trim()
        val path = binding.etNextcloudRemotePath.text.toString().trim()
        if (cloudOn && (url.isEmpty() || user.isEmpty() || pass.isEmpty() || path.isEmpty())) {
            Toast.makeText(this, "Please fill in all Nextcloud fields", Toast.LENGTH_LONG).show()
            return
        }

        // Different server/note: drop the cached copy and queued changes of the old one.
        if (cloudOn && (url != prefs.nextcloudUrl || user != prefs.nextcloudUsername || path != prefs.nextcloudRemotePath)) {
            TaskRepository.resetRemote(this)
        }
        prefs.isCloudSyncEnabled = cloudOn
        if (cloudOn) {
            prefs.nextcloudUrl = url
            prefs.nextcloudUsername = user
            prefs.nextcloudPassword = pass
            prefs.nextcloudRemotePath = path
            if (prefs.fileUriString == null) prefs.fileName = path.substringAfterLast('/')
        }

        // Nextcloud on: 15-min pull + an immediate one. Off: cancel background sync.
        TaskRepository.schedulePeriodic(this)
        TaskRepository.syncNow(this)
        TaskRepository.refreshWidgets(this)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            ObsidianTasksWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view)
        }

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }

    override fun finish() {
        super.finish()
        finishAndRemoveTask()
    }
}
