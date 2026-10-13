package com.glassvault.widget.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object MarkdownTaskManager {

    private const val TAG = "MarkdownTaskManager"

    // Matches markdown checkbox lists:
    // Group 1: Leading indentation
    // Group 2: Bullet marker (- or * or + or 1.)
    // Group 3: Checkbox mark (' ' or 'x' or 'X')
    // Group 4: Task description content
    private val TASK_PATTERN = Regex("""^(\s*)([-*+]|\d+\.)\s+\[([ xX])\]\s*(.*)$""")
    private val TAG_PATTERN = Regex("""#([a-zA-Z0-9_\-]+)""")

    /**
     * Reads and parses all checkbox tasks from the given document Uri.
     */
    fun loadTasks(context: Context, uri: Uri, includeCompleted: Boolean = true): List<TaskItem> {
        val tasks = mutableListOf<TaskItem>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val match = TASK_PATTERN.matchEntire(line)
                        if (match != null) {
                            val indent = match.groupValues[1]
                            val statusChar = match.groupValues[3]
                            val rawContent = match.groupValues[4]
                            val isCompleted = statusChar.equals("x", ignoreCase = true)

                            if (includeCompleted || !isCompleted) {
                                // Extract Obsidian tag if present (e.g., #todo, #urgent)
                                val tagMatch = TAG_PATTERN.find(rawContent)
                                val tag = tagMatch?.value

                                val cleanTitle = if (tag != null) {
                                    rawContent.replace(tag, "").trim()
                                } else {
                                    rawContent.trim()
                                }

                                val indentLevel = (indent.length / 2).coerceAtMost(4)

                                tasks.add(
                                    TaskItem(
                                        id = index,
                                        lineIndex = index,
                                        originalLine = line,
                                        title = if (cleanTitle.isNotEmpty()) cleanTitle else rawContent,
                                        isCompleted = isCompleted,
                                        indentLevel = indentLevel,
                                        tag = tag
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading markdown tasks from $uri", e)
        }
        return tasks
    }

    /**
     * Toggles the checkbox on the specified line number in the markdown file.
     */
    @Synchronized
    fun toggleTask(context: Context, uri: Uri, lineIndex: Int): Boolean {
        try {
            val lines = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                    lines.add(line)
                }
            } ?: return false

            if (lineIndex < 0 || lineIndex >= lines.size) {
                Log.w(TAG, "Target lineIndex $lineIndex out of bounds (total lines: ${lines.size})")
                return false
            }

            val targetLine = lines[lineIndex]
            val match = TASK_PATTERN.matchEntire(targetLine) ?: return false

            val indent = match.groupValues[1]
            val bullet = match.groupValues[2]
            val statusChar = match.groupValues[3]
            val content = match.groupValues[4]

            val newStatus = if (statusChar.equals("x", ignoreCase = true)) " " else "x"
            val updatedLine = "$indent$bullet [$newStatus] $content"
            lines[lineIndex] = updatedLine

            return writeLines(context, uri, lines)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling task at line $lineIndex", e)
            return false
        }
    }

    /**
     * Updates the text title of an existing task at the specified line number.
     */
    @Synchronized
    fun updateTaskTitle(context: Context, uri: Uri, lineIndex: Int, newTitle: String): Boolean {
        try {
            val trimmed = newTitle.trim()
            if (trimmed.isEmpty()) return false

            val lines = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                    lines.add(line)
                }
            } ?: return false

            if (lineIndex < 0 || lineIndex >= lines.size) return false

            val targetLine = lines[lineIndex]
            val match = TASK_PATTERN.matchEntire(targetLine) ?: return false

            val indent = match.groupValues[1]
            val bullet = match.groupValues[2]
            val statusChar = match.groupValues[3]

            val updatedLine = "$indent$bullet [$statusChar] $trimmed"
            lines[lineIndex] = updatedLine

            return writeLines(context, uri, lines)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating task title at line $lineIndex", e)
            return false
        }
    }

    /**
     * Deletes a task from the markdown file at the specified line number.
     */
    @Synchronized
    fun deleteTask(context: Context, uri: Uri, lineIndex: Int): Boolean {
        try {
            val lines = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                    lines.add(line)
                }
            } ?: return false

            if (lineIndex < 0 || lineIndex >= lines.size) return false
            lines.removeAt(lineIndex)

            return writeLines(context, uri, lines)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting task at line $lineIndex", e)
            return false
        }
    }

    /**
     * Appends a new to-do checkbox item to the markdown file.
     */
    @Synchronized
    fun addTask(context: Context, uri: Uri, taskTitle: String): Boolean {
        try {
            val trimmed = taskTitle.trim()
            if (trimmed.isEmpty()) return false

            val existingContent = readRawContent(context, uri) ?: ""
            val separator = if (existingContent.endsWith("\n") || existingContent.isEmpty()) "" else "\n"
            val newContent = "$existingContent$separator- [ ] $trimmed\n"

            return writeRawContent(context, uri, newContent)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding task: $taskTitle", e)
            return false
        }
    }

    /**
     * Reads the entire raw text of the markdown file.
     */
    fun readRawContent(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading raw content from $uri", e)
            null
        }
    }

    /**
     * Overwrites the markdown file with the provided raw text.
     */
    @Synchronized
    fun writeRawContent(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw content to $uri", e)
            false
        }
    }

    private fun writeLines(context: Context, uri: Uri, lines: List<String>): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    for (i in lines.indices) {
                        writer.write(lines[i])
                        if (i < lines.size - 1) {
                            writer.write("\n")
                        }
                    }
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing lines to $uri", e)
            false
        }
    }
}
