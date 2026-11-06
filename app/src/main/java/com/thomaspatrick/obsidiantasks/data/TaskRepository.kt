package com.thomaspatrick.obsidiantasks.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.thomaspatrick.obsidiantasks.ObsidianTasksWidgetProvider
import com.thomaspatrick.obsidiantasks.R
import com.thomaspatrick.obsidiantasks.sync.NextcloudWebDavSyncWorker
import com.thomaspatrick.obsidiantasks.sync.RemoteTaskStore
import com.thomaspatrick.obsidiantasks.sync.WebDavClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One place that decides where tasks come from and where changes go:
 *  - Nextcloud ON:  read/write the Nextcloud copy only (queued + conditional uploads).
 *                   The phone's vault file is never written by the widget.
 *  - Nextcloud OFF: read/write the local note picked in settings.
 */
object TaskRepository {

    private const val SYNC_NOW = "NEXTCLOUD_SYNC"
    private const val SYNC_PERIODIC = "NEXTCLOUD_PERIODIC_SYNC"
    private const val STATE_FILE = "nextcloud_state.json"

    @Volatile private var store: RemoteTaskStore? = null
    @Volatile private var storeKey: String? = null

    fun isRemote(prefs: AppPreferences) =
        prefs.isCloudSyncEnabled && prefs.nextcloudUrl.isNotEmpty() && prefs.nextcloudUsername.isNotEmpty() &&
            prefs.nextcloudPassword.isNotEmpty() && prefs.nextcloudRemotePath.isNotEmpty()

    @Synchronized
    fun remoteStore(context: Context): RemoteTaskStore? {
        val prefs = AppPreferences(context)
        if (!isRemote(prefs)) return null
        val key = listOf(prefs.nextcloudUrl, prefs.nextcloudUsername, prefs.nextcloudPassword, prefs.nextcloudRemotePath)
            .joinToString("\u0000")
        if (store == null || storeKey != key) {
            val transport = WebDavClient.transport(
                prefs.nextcloudUrl, prefs.nextcloudUsername, prefs.nextcloudPassword, prefs.nextcloudRemotePath
            )
            store = RemoteTaskStore(File(context.applicationContext.filesDir, STATE_FILE), transport)
            storeKey = key
        }
        return store
    }

    /** Server/path changed: forget the cached copy so we never apply ops to the wrong note. */
    @Synchronized
    fun resetRemote(context: Context) {
        File(context.applicationContext.filesDir, STATE_FILE).delete()
        store = null
        storeKey = null
    }

    fun loadTasks(context: Context): List<TaskItem> {
        val prefs = AppPreferences(context)
        remoteStore(context)?.let { s ->
            return MarkdownTaskManager.parseLines(s.viewLines() ?: return emptyList())
        }
        val uri = prefs.fileUriString ?: return emptyList()
        return MarkdownTaskManager.loadTasks(context, Uri.parse(uri))
    }

    /** "3 pending", "Synced 12:01", "2 waiting" etc. for the header pill. */
    fun statusText(context: Context, pending: Int): String {
        val s = remoteStore(context) ?: return "$pending pending"
        return when {
            s.base == null -> if (s.error != null) "Can't reach Nextcloud" else "Loading…"
            s.ops.isNotEmpty() -> "$pending pending · ${s.ops.size} to upload"
            s.error != null -> "$pending pending · offline"
            else -> "$pending pending"
        }
    }

    fun toggle(context: Context, lineIndex: Int, expectedLine: String?) =
        change(context, RemoteTaskStore.TYPE_TOGGLE, lineIndex, expectedLine, null) { uri ->
            MarkdownTaskManager.toggleTask(context, uri, lineIndex, expectedLine)
        }

    fun add(context: Context, title: String) =
        change(context, RemoteTaskStore.TYPE_ADD, -1, null, title) { uri ->
            MarkdownTaskManager.addTask(context, uri, title)
        }

    fun rename(context: Context, lineIndex: Int, expectedLine: String?, title: String) =
        change(context, RemoteTaskStore.TYPE_RENAME, lineIndex, expectedLine, title) { uri ->
            MarkdownTaskManager.updateTaskTitle(context, uri, lineIndex, title, expectedLine)
        }

    fun delete(context: Context, lineIndex: Int, expectedLine: String?) =
        change(context, RemoteTaskStore.TYPE_DELETE, lineIndex, expectedLine, null) { uri ->
            MarkdownTaskManager.deleteTask(context, uri, lineIndex, expectedLine)
        }

    private fun change(
        context: Context,
        type: String,
        lineIndex: Int,
        expectedLine: String?,
        title: String?,
        local: (Uri) -> Boolean
    ): Boolean {
        val s = remoteStore(context)
        if (s != null) {
            val ok = s.queue(type, lineIndex, expectedLine, title)
            if (ok) syncNow(context)
            return ok
        }
        val uri = AppPreferences(context).fileUriString ?: return false
        return local(Uri.parse(uri))
    }

    // ---- background sync scheduling ----

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Run a sync soon. APPEND_OR_REPLACE never cancels an upload that is mid-flight. */
    fun syncNow(context: Context) {
        if (!isRemote(AppPreferences(context))) return
        val req = OneTimeWorkRequestBuilder<NextcloudWebDavSyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!isRemote(AppPreferences(context))) {
            wm.cancelUniqueWork(SYNC_PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<NextcloudWebDavSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        wm.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun refreshWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, ObsidianTasksWidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_view)
        for (id in ids) ObsidianTasksWidgetProvider.updateAppWidget(context, mgr, id)
    }
}
