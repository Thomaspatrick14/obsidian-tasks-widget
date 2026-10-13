package com.glassvault.widget.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.glassvault.widget.LiquidTodoWidgetProvider
import com.glassvault.widget.R
import com.glassvault.widget.data.AppPreferences
import com.glassvault.widget.data.MarkdownTaskManager
import com.glassvault.widget.databinding.ActivityConfigBinding
import com.glassvault.widget.sync.NextcloudWebDavSyncWorker
import com.glassvault.widget.sync.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
        if (selectedUri == null && prefs.fileUriString == null) {
            Toast.makeText(this, "Please select an Obsidian markdown note first", Toast.LENGTH_LONG).show()
            return
        }

        prefs.showCompleted = binding.switchShowCompleted.isChecked
        prefs.isCloudSyncEnabled = binding.switchCloudSync.isChecked

        if (prefs.isCloudSyncEnabled) {
            prefs.nextcloudUrl = binding.etNextcloudUrl.text.toString().trim()
            prefs.nextcloudUsername = binding.etNextcloudUser.text.toString().trim()
            prefs.nextcloudPassword = binding.etNextcloudPass.text.toString().trim()
            prefs.nextcloudRemotePath = binding.etNextcloudRemotePath.text.toString().trim()

            // Schedule periodic background sync (every 15 min with unmetered or connected network)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicSyncRequest = PeriodicWorkRequestBuilder<NextcloudWebDavSyncWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(constraints).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "NEXTCLOUD_PERIODIC_SYNC",
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicSyncRequest
            )
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("NEXTCLOUD_PERIODIC_SYNC")
        }

        // Notify AppWidgetManager to update widgets
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, LiquidTodoWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (widgetIds.isNotEmpty()) {
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
            for (id in widgetIds) {
                LiquidTodoWidgetProvider.updateAppWidget(this, appWidgetManager, id)
            }
        }

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            LiquidTodoWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view)
        }

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}
