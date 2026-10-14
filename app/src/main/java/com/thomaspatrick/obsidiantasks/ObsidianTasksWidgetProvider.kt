package com.thomaspatrick.obsidiantasks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.MarkdownTaskManager
import com.thomaspatrick.obsidiantasks.ui.QuickAddActivity
import com.thomaspatrick.obsidiantasks.ui.WidgetConfigActivity

class ObsidianTasksWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        const val ACTION_TOGGLE_TASK = "com.thomaspatrick.obsidiantasks.ACTION_TOGGLE_TASK"
        const val ACTION_MANUAL_SYNC = "com.thomaspatrick.obsidiantasks.ACTION_MANUAL_SYNC"
        const val ACTION_REFRESH = "com.thomaspatrick.obsidiantasks.ACTION_REFRESH"

        const val EXTRA_LINE_INDEX = "extra_line_index"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_TOGGLE_DONE = "extra_toggle_done"
        const val EXTRA_CLICK_ACTION = "extra_click_action"
        const val EXTRA_TASK_TEXT = "extra_task_text"

        const val CLICK_ACTION_TOGGLE = "action_toggle"
        const val CLICK_ACTION_EDIT = "action_edit"
        const val CLICK_ACTION_TOGGLE_DONE = "action_toggle_done"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = AppPreferences(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Header - Note title & stats
            val displayName = prefs.fileName.removeSuffix(".md")
            views.setTextViewText(R.id.tv_widget_title, displayName)

            val uriString = prefs.fileUriString
            if (uriString != null) {
                try {
                    val tasks = MarkdownTaskManager.loadTasks(context, Uri.parse(uriString))
                    val pendingCount = tasks.count { !it.isCompleted }
                    views.setTextViewText(R.id.tv_widget_count, "$pendingCount pending")
                } catch (e: Exception) {
                    views.setTextViewText(R.id.tv_widget_count, "Error reading note")
                }
            } else {
                views.setTextViewText(R.id.tv_widget_count, "Tap to setup")
            }

            // Bind RemoteViewsService for the scrollable list
            val serviceIntent = Intent(context, ObsidianTasksWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.fromParts("content", appWidgetId.toString(), null)
            }
            views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty_view)

            // PendingIntent Template for List Items (Checking / unchecking task)
            val toggleIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = ACTION_TOGGLE_TASK
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list_view, togglePendingIntent)

            // Title Click -> Open Settings / Config
            val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            val configPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tv_widget_title, configPendingIntent)

            // Quick Add Button -> Open Floating Dialog
            val addIntent = Intent(context, QuickAddActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
            }
            val addPendingIntent = PendingIntent.getActivity(
                context,
                1001,
                addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_add, addPendingIntent)

            // Sync Button -> Trigger Manual Nextcloud Sync Broadcast
            val syncIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = ACTION_MANUAL_SYNC
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val syncPendingIntent = PendingIntent.getBroadcast(
                context,
                1002,
                syncIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_sync, syncPendingIntent)

            // Settings Button -> Open Settings / Config
            val settingsIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            val settingsPendingIntent = PendingIntent.getActivity(
                context,
                1003,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_settings, settingsPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
