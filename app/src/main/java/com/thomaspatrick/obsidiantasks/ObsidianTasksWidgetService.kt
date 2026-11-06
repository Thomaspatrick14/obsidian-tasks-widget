package com.thomaspatrick.obsidiantasks

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.thomaspatrick.obsidiantasks.data.AppPreferences
import com.thomaspatrick.obsidiantasks.data.MarkdownLinks
import com.thomaspatrick.obsidiantasks.data.TaskRepository
import com.thomaspatrick.obsidiantasks.data.TaskItem

class ObsidianTasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ObsidianTasksViewsFactory(applicationContext, intent)
    }
}

sealed class WidgetItem {
    data class TaskItemRow(val task: TaskItem) : WidgetItem()
    data class DoneHeaderRow(val count: Int, val isExpanded: Boolean) : WidgetItem()
}

class ObsidianTasksViewsFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private val prefs = AppPreferences(context)
    private var displayItems = listOf<WidgetItem>()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        try {
            val rawTasks = TaskRepository.loadTasks(context)

            val activeTasks = rawTasks.filter { !it.isCompleted }
            val completedTasks = rawTasks.filter { it.isCompleted }

            val items = mutableListOf<WidgetItem>()
            // 1. Add all active tasks
            for (task in activeTasks) {
                items.add(WidgetItem.TaskItemRow(task))
            }

            // 2. If there are completed tasks, add collapsible "Done" header
            if (completedTasks.isNotEmpty()) {
                items.add(WidgetItem.DoneHeaderRow(completedTasks.size, prefs.isDoneExpanded))
                // 3. If expanded by user tap, show completed tasks
                if (prefs.isDoneExpanded) {
                    for (task in completedTasks) {
                        items.add(WidgetItem.TaskItemRow(task))
                    }
                }
            }

            displayItems = items
        } catch (e: Exception) {
            displayItems = emptyList()
        }
    }

    override fun onDestroy() {
        displayItems = emptyList()
    }

    override fun getCount(): Int = displayItems.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= displayItems.size) {
            return RemoteViews(context.packageName, R.layout.widget_task_item)
        }

        val item = displayItems[position]
        val views = RemoteViews(context.packageName, R.layout.widget_task_item)

        when (item) {
            is WidgetItem.TaskItemRow -> {
                views.setViewVisibility(R.id.done_header_container, View.GONE)
                views.setViewVisibility(R.id.task_item_container, View.VISIBLE)
                val task = item.task

                // Markdown links show as their label; the ↗ button opens the link.
                val rendered = MarkdownLinks.render(task.title)
                val prefix = if (task.indentLevel > 0) "  ".repeat(task.indentLevel) + "↳ " else ""
                val spannable = SpannableString(prefix + rendered.text)

                if (task.isCompleted) {
                    views.setImageViewResource(R.id.iv_checkbox, R.drawable.ic_checkbox_checked)
                    spannable.setSpan(StrikethroughSpan(), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    views.setTextColor(R.id.tv_task_title, Color.parseColor("#71717A"))
                } else {
                    views.setImageViewResource(R.id.iv_checkbox, R.drawable.ic_checkbox_unchecked)
                    for (r in rendered.linkRanges) {
                        spannable.setSpan(
                            ForegroundColorSpan(Color.parseColor("#8AB4FF")),
                            prefix.length + r.first, prefix.length + r.last + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    views.setTextColor(R.id.tv_task_title, Color.parseColor("#F4F4F5"))
                }
                views.setTextViewText(R.id.tv_task_title, spannable)

                val url = rendered.firstUrl?.takeIf { MarkdownLinks.isOpenable(it) }
                if (url != null) {
                    views.setViewVisibility(R.id.iv_open_link, View.VISIBLE)
                    views.setOnClickFillInIntent(R.id.iv_open_link, Intent().apply {
                        putExtra(ObsidianTasksWidgetProvider.EXTRA_CLICK_ACTION, ObsidianTasksWidgetProvider.CLICK_ACTION_OPEN_LINK)
                        putExtra(ObsidianTasksWidgetProvider.EXTRA_URL, url)
                        putExtra(ObsidianTasksWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
                    })
                } else {
                    views.setViewVisibility(R.id.iv_open_link, View.GONE)
                }

                // Tag badge
                if (!task.tag.isNullOrEmpty()) {
                    views.setViewVisibility(R.id.tv_task_tag, View.VISIBLE)
                    views.setTextViewText(R.id.tv_task_tag, task.tag)
                } else {
                    views.setViewVisibility(R.id.tv_task_tag, View.GONE)
                }

                // 1. Clicking CHECKBOX toggles task
                val toggleIntent = Intent().apply {
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_CLICK_ACTION, ObsidianTasksWidgetProvider.CLICK_ACTION_TOGGLE)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_LINE_INDEX, task.lineIndex)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_ORIGINAL_LINE, task.originalLine)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.iv_checkbox, toggleIntent)

                // 2. Clicking TEXT / ROW opens Task Edit Dialog
                val editIntent = Intent().apply {
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_CLICK_ACTION, ObsidianTasksWidgetProvider.CLICK_ACTION_EDIT)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_LINE_INDEX, task.lineIndex)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_TASK_TEXT, task.title)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_ORIGINAL_LINE, task.originalLine)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.task_item_container, editIntent)
                views.setOnClickFillInIntent(R.id.tv_task_title, editIntent)
            }

            is WidgetItem.DoneHeaderRow -> {
                views.setViewVisibility(R.id.task_item_container, View.GONE)
                views.setViewVisibility(R.id.done_header_container, View.VISIBLE)

                views.setTextViewText(R.id.tv_done_header_title, "Done (${item.count})")
                views.setImageViewResource(
                    R.id.iv_done_chevron,
                    if (item.isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
                )
                views.setTextViewText(
                    R.id.tv_done_action_hint,
                    if (item.isExpanded) "tap to hide" else "tap to view"
                )

                // Fill-in Intent for expanding/collapsing the "Done" section
                val fillInIntent = Intent().apply {
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_CLICK_ACTION, ObsidianTasksWidgetProvider.CLICK_ACTION_TOGGLE_DONE)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_TOGGLE_DONE, true)
                    putExtra(ObsidianTasksWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.done_header_container, fillInIntent)
            }
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
