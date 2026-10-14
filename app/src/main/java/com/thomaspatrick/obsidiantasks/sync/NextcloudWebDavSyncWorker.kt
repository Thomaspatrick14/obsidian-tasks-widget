package com.thomaspatrick.obsidiantasks.sync

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thomaspatrick.obsidiantasks.ObsidianTasksWidgetProvider
import com.thomaspatrick.obsidiantasks.R
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.MarkdownTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NextcloudWebDavSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val prefs = AppPreferences(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.isCloudSyncEnabled) {
            Log.d(TAG, "Cloud sync is disabled. Skipping.")
            return@withContext Result.success()
        }

        val uriString = prefs.fileUriString
        if (uriString.isNullOrEmpty()) {
            Log.w(TAG, "No local file URI configured for sync.")
            return@withContext Result.failure()
        }

        val localUri = Uri.parse(uriString)
        val serverUrl = prefs.nextcloudUrl
        val username = prefs.nextcloudUsername
        val password = prefs.nextcloudPassword
        val remotePath = prefs.nextcloudRemotePath

        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty() || remotePath.isEmpty()) {
            Log.w(TAG, "Incomplete Nextcloud WebDAV credentials.")
            return@withContext Result.failure()
        }

        val mode = inputData.getString(KEY_SYNC_MODE) ?: MODE_UPLOAD

        try {
            if (mode == MODE_DOWNLOAD) {
                // Pull changes from Nextcloud
                val downloadResult = WebDavClient.downloadFile(serverUrl, username, password, remotePath)
                if (downloadResult.isSuccess) {
                    val remoteContent = downloadResult.getOrThrow()
                    MarkdownTaskManager.writeRawContent(applicationContext, localUri, remoteContent)
                    updateSyncSuccess()
                    return@withContext Result.success()
                } else {
                    updateSyncFailure(downloadResult.exceptionOrNull()?.message ?: "Download failed")
                    return@withContext Result.retry()
                }
            } else {
                // Push local changes to Nextcloud
                val localContent = MarkdownTaskManager.readRawContent(applicationContext, localUri)
                if (localContent == null) {
                    updateSyncFailure("Could not read local file")
                    return@withContext Result.failure()
                }

                val uploadResult = WebDavClient.uploadFile(serverUrl, username, password, remotePath, localContent)
                if (uploadResult.isSuccess) {
                    val etag = uploadResult.getOrThrow()
                    prefs.lastKnownEtag = etag
                    updateSyncSuccess()
                    return@withContext Result.success()
                } else {
                    updateSyncFailure(uploadResult.exceptionOrNull()?.message ?: "Upload failed")
                    return@withContext Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WebDAV sync", e)
            updateSyncFailure(e.localizedMessage ?: "Unknown error")
            return@withContext Result.failure()
        }
    }

    private fun updateSyncSuccess() {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = timeFormat.format(Date())
        prefs.lastSyncTimestamp = System.currentTimeMillis()
        prefs.lastSyncStatus = "Synced at $formattedTime"
        notifyWidgetUpdated()
    }

    private fun updateSyncFailure(reason: String) {
        prefs.lastSyncStatus = "Failed: $reason"
        notifyWidgetUpdated()
    }

    private fun notifyWidgetUpdated() {
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val componentName = ComponentName(applicationContext, ObsidianTasksWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
    }

    companion object {
        private const val TAG = "NextcloudSyncWorker"
        const val KEY_SYNC_MODE = "key_sync_mode"
        const val MODE_UPLOAD = "mode_upload"
        const val MODE_DOWNLOAD = "mode_download"
    }
}
