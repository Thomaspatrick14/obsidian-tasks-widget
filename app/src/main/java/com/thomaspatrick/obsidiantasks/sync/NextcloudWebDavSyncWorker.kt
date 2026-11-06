package com.thomaspatrick.obsidiantasks.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pulls the latest note from Nextcloud and uploads any queued widget changes
 * with conditional (If-Match) PUTs. Never reads or writes the phone's local
 * vault file, and never blindly overwrites the server copy.
 */
class NextcloudWebDavSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = TaskRepository.remoteStore(applicationContext)
            ?: return@withContext Result.success()   // Nextcloud off or not configured

        val ok = store.sync()
        val prefs = AppPreferences(applicationContext)
        if (ok) {
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            prefs.lastSyncStatus = "Synced at " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        } else {
            prefs.lastSyncStatus = "Failed: ${store.error}"
            Log.w(TAG, "Sync failed: ${store.error}")
        }
        TaskRepository.refreshWidgets(applicationContext)

        when {
            ok -> Result.success()
            store.ops.isNotEmpty() && runAttemptCount < 8 -> Result.retry()   // keep trying to deliver changes
            else -> Result.success()                                          // pull-only failure: next periodic run
        }
    }

    companion object {
        private const val TAG = "NextcloudSyncWorker"
    }
}
