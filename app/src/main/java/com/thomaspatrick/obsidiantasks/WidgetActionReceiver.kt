package com.thomaspatrick.obsidiantasks

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.MarkdownLinks
import com.thomaspatrick.obsidiantasks.data.TaskRepository
import com.thomaspatrick.obsidiantasks.ui.QuickAddActivity

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPreferences(context)

        when (intent.action) {
            ObsidianTasksWidgetProvider.ACTION_TOGGLE_TASK -> {
                val clickAction = intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_CLICK_ACTION)
                    ?: ObsidianTasksWidgetProvider.CLICK_ACTION_TOGGLE
                val isToggleDone = intent.getBooleanExtra(ObsidianTasksWidgetProvider.EXTRA_TOGGLE_DONE, false)
                val widgetId = intent.getIntExtra(
                    ObsidianTasksWidgetProvider.EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val lineIndex = intent.getIntExtra(ObsidianTasksWidgetProvider.EXTRA_LINE_INDEX, -1)

                // 1. Tapping "Done" header collapses / expands completed tasks
                if (clickAction == ObsidianTasksWidgetProvider.CLICK_ACTION_TOGGLE_DONE || isToggleDone) {
                    prefs.isDoneExpanded = !prefs.isDoneExpanded
                    triggerHapticFeedback(context)

                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list_view)
                    } else {
                        val componentName = ComponentName(context, ObsidianTasksWidgetProvider::class.java)
                        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                        appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
                    }
                    return
                }

                // Tapping the ↗ button opens the task's link (browser, or Obsidian for [[wikilinks]])
                if (clickAction == ObsidianTasksWidgetProvider.CLICK_ACTION_OPEN_LINK) {
                    val url = intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_URL)
                    if (url != null && MarkdownLinks.isOpenable(url)) {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            Log.w("WidgetActionReceiver", "No app to open $url", e)
                        }
                    }
                    return
                }

                // 2. Tapping text opens Task Edit Dialog
                if (clickAction == ObsidianTasksWidgetProvider.CLICK_ACTION_EDIT) {
                    val taskText = intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_TASK_TEXT) ?: ""
                    val editIntent = Intent(context, QuickAddActivity::class.java).apply {
                        putExtra(QuickAddActivity.EXTRA_MODE, QuickAddActivity.MODE_EDIT)
                        putExtra(ObsidianTasksWidgetProvider.EXTRA_LINE_INDEX, lineIndex)
                        putExtra(ObsidianTasksWidgetProvider.EXTRA_TASK_TEXT, taskText)
                        putExtra(
                            ObsidianTasksWidgetProvider.EXTRA_ORIGINAL_LINE,
                            intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_ORIGINAL_LINE)
                        )
                        putExtra(ObsidianTasksWidgetProvider.EXTRA_WIDGET_ID, widgetId)
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

                if (lineIndex >= 0) {
                    val originalLine = intent.getStringExtra(ObsidianTasksWidgetProvider.EXTRA_ORIGINAL_LINE)
                    // Local mode: edits the note. Nextcloud mode: queues the change and
                    // starts a conditional upload; the phone's vault file is not touched.
                    if (TaskRepository.toggle(context, lineIndex, originalLine)) {
                        triggerHapticFeedback(context)
                    }
                    TaskRepository.refreshWidgets(context)
                }
            }

            ObsidianTasksWidgetProvider.ACTION_MANUAL_SYNC -> {
                // Pull the latest copy (and push anything queued). Never overwrites the server.
                TaskRepository.syncNow(context)
                TaskRepository.refreshWidgets(context)
            }

            ObsidianTasksWidgetProvider.ACTION_REFRESH -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, ObsidianTasksWidgetProvider::class.java)
                val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
                for (id in widgetIds) {
                    ObsidianTasksWidgetProvider.updateAppWidget(context, appWidgetManager, id)
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
