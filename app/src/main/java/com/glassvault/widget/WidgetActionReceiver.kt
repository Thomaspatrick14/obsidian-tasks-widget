package com.glassvault.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.glassvault.widget.data.AppPreferences
import com.glassvault.widget.data.MarkdownTaskManager
import com.glassvault.widget.sync.NextcloudWebDavSyncWorker

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPreferences(context)
        val uriString = prefs.fileUriString

        when (intent.action) {
            LiquidTodoWidgetProvider.ACTION_TOGGLE_TASK -> {
                val clickAction = intent.getStringExtra(LiquidTodoWidgetProvider.EXTRA_CLICK_ACTION)
                    ?: LiquidTodoWidgetProvider.CLICK_ACTION_TOGGLE
                val isToggleDone = intent.getBooleanExtra(LiquidTodoWidgetProvider.EXTRA_TOGGLE_DONE, false)
                val widgetId = intent.getIntExtra(
                    LiquidTodoWidgetProvider.EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val lineIndex = intent.getIntExtra(LiquidTodoWidgetProvider.EXTRA_LINE_INDEX, -1)

                // 1. Tapping "Done" header collapses / expands completed tasks
                if (clickAction == LiquidTodoWidgetProvider.CLICK_ACTION_TOGGLE_DONE || isToggleDone) {
                    prefs.isDoneExpanded = !prefs.isDoneExpanded
                    triggerHapticFeedback(context)

                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list_view)
                    } else {
                        val componentName = ComponentName(context, LiquidTodoWidgetProvider::class.java)
                        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                        appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
                    }
                    return
                }

                // 2. Tapping text opens Task Edit Dialog
                if (clickAction == LiquidTodoWidgetProvider.CLICK_ACTION_EDIT) {
                    val taskText = intent.getStringExtra(LiquidTodoWidgetProvider.EXTRA_TASK_TEXT) ?: ""
                    val editIntent = Intent(context, com.glassvault.widget.ui.QuickAddActivity::class.java).apply {
                        putExtra(com.glassvault.widget.ui.QuickAddActivity.EXTRA_MODE, com.glassvault.widget.ui.QuickAddActivity.MODE_EDIT)
                        putExtra(LiquidTodoWidgetProvider.EXTRA_LINE_INDEX, lineIndex)
                        putExtra(LiquidTodoWidgetProvider.EXTRA_TASK_TEXT, taskText)
                        putExtra(LiquidTodoWidgetProvider.EXTRA_WIDGET_ID, widgetId)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                        )
                    }
                    context.startActivity(editIntent)
                    return
                }

                if (lineIndex >= 0 && !uriString.isNullOrEmpty()) {
                    val uri = Uri.parse(uriString)

                    // 1. Flip checkbox in markdown note
                    val success = MarkdownTaskManager.toggleTask(context, uri, lineIndex)
                    if (success) {
                        triggerHapticFeedback(context)

                        // 2. Refresh Widget UI immediately
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list_view)
                            LiquidTodoWidgetProvider.updateAppWidget(context, appWidgetManager, widgetId)
                        } else {
                            val componentName = ComponentName(context, LiquidTodoWidgetProvider::class.java)
                            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                            appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
                            for (id in widgetIds) {
                                LiquidTodoWidgetProvider.updateAppWidget(context, appWidgetManager, id)
                            }
                        }

                        // 3. Trigger immediate background WebDAV sync to Nextcloud!
                        if (prefs.isCloudSyncEnabled) {
                            val syncRequest = OneTimeWorkRequestBuilder<NextcloudWebDavSyncWorker>()
                                .setInputData(workDataOf(NextcloudWebDavSyncWorker.KEY_SYNC_MODE to NextcloudWebDavSyncWorker.MODE_UPLOAD))
                                .build()

                            WorkManager.getInstance(context).enqueueUniqueWork(
                                "NEXTCLOUD_PUSH_ON_TOGGLE",
                                ExistingWorkPolicy.REPLACE,
                                syncRequest
                            )
                        }
                    }
                }
            }

            LiquidTodoWidgetProvider.ACTION_MANUAL_SYNC -> {
                if (prefs.isCloudSyncEnabled) {
                    val syncRequest = OneTimeWorkRequestBuilder<NextcloudWebDavSyncWorker>()
                        .setInputData(workDataOf(NextcloudWebDavSyncWorker.KEY_SYNC_MODE to NextcloudWebDavSyncWorker.MODE_DOWNLOAD))
                        .build()

                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "NEXTCLOUD_MANUAL_SYNC",
                        ExistingWorkPolicy.REPLACE,
                        syncRequest
                    )
                }
            }

            LiquidTodoWidgetProvider.ACTION_REFRESH -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, LiquidTodoWidgetProvider::class.java)
                val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
                for (id in widgetIds) {
                    LiquidTodoWidgetProvider.updateAppWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun triggerHapticFeedback(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(20)
                }
            }
        } catch (e: Exception) {
            Log.w("WidgetActionReceiver", "Haptic feedback unavailable", e)
        }
    }
}
