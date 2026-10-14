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
    // Group 2: Bullet marker (- or * or + or 1., optional)
    // Group 3: Checkbox mark (' ' or 'x' or 'X')
    // Group 4: Task description content
    val TASK_PATTERN = Regex("""^(\s*)(?:([-*+]|\d+\.)\s+)?\[([ xX])\]\s*(.*)$""")
    val TAG_PATTERN = Regex("""#([a-zA-Z0-9_\-]+)""")

    val DONE_HEADER_PATTERN = Regex("""(?i)^\s*(?:#{1,6}\s+|\*{2}|_{2})?\s*(done|completed|finished|archive|archived)\b.*$""")
    val TODO_HEADER_PATTERN = Regex("""(?i)^\s*(?:#{1,6}\s+|\*{2}|_{2})?\s*(to[\s\-]?do|tasks|pending|active)\b.*$""")

    fun isDoneHeader(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (TASK_PATTERN.matches(line)) return false
        return DONE_HEADER_PATTERN.matches(trimmed)
    }

    fun isTodoHeader(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (TASK_PATTERN.matches(line)) return false
        return TODO_HEADER_PATTERN.matches(trimmed)
    }

    fun isIndentedSubLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (TASK_PATTERN.matches(line)) return false
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return false
        return line.startsWith("  ") || line.startsWith("\t")
    }

    fun detectBulletStyle(lines: List<String>): String {
        for (line in lines) {
            val match = TASK_PATTERN.matchEntire(line)
            if (match != null) {
                val b = match.groupValues[2]
                if (b.isNotEmpty()) return b
            }
        }
        return "-"
    }

    /**
     * Determines the optimal zero-based line index where a new uncompleted task should be inserted.
     */
    fun findInsertionIndex(lines: List<String>): Int {
        if (lines.isEmpty()) return 0

        // 1. Check for DONE / Completed / Archive header
        val doneHeaderIndex = lines.indexOfFirst { isDoneHeader(it) }

        if (doneHeaderIndex != -1) {
            // Find last uncompleted task before the DONE header
            val lastActiveBeforeDone = (doneHeaderIndex - 1 downTo 0).firstOrNull { idx ->
                val match = TASK_PATTERN.matchEntire(lines[idx])
                match != null && match.groupValues[3] == " "
            }

            if (lastActiveBeforeDone != null) {
                var insertAt = lastActiveBeforeDone + 1
                while (insertAt < doneHeaderIndex && isIndentedSubLine(lines[insertAt])) {
                    insertAt++
                }
                return insertAt
            }

            // No active task before DONE. Check if there's a TODO header before DONE.
            val todoHeaderIndex = (0 until doneHeaderIndex).firstOrNull { isTodoHeader(lines[it]) }
            if (todoHeaderIndex != null) {
                val insertAt = todoHeaderIndex + 1
                if (insertAt < doneHeaderIndex && lines[insertAt].isBlank()) {
                    return insertAt
                }
                return insertAt
            }

            // Otherwise, insert before any blank lines immediately preceding the DONE header
            var blankStart = doneHeaderIndex
            while (blankStart > 0 && lines[blankStart - 1].isBlank()) {
                blankStart--
            }
            return blankStart
        }

        // 2. No DONE header. Check for TODO header
        val todoHeaderIndex = lines.indexOfFirst { isTodoHeader(it) }
        if (todoHeaderIndex != -1) {
            var sectionEnd = lines.size
            for (i in (todoHeaderIndex + 1) until lines.size) {
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("#") && !TASK_PATTERN.matches(trimmed)) {
                    sectionEnd = i
                    break
                }
            }

            val lastActiveInSection = (sectionEnd - 1 downTo todoHeaderIndex + 1).firstOrNull { idx ->
                val match = TASK_PATTERN.matchEntire(lines[idx])
                match != null && match.groupValues[3] == " "
            }

            if (lastActiveInSection != null) {
                var insertAt = lastActiveInSection + 1
                while (insertAt < sectionEnd && isIndentedSubLine(lines[insertAt])) {
                    insertAt++
                }
                return insertAt
            }

            val insertAt = todoHeaderIndex + 1
            if (insertAt < sectionEnd && lines[insertAt].isBlank()) {
                return insertAt
            }
            return insertAt
        }

        // 3. No headers. Check if there are completed tasks
        val firstCompletedIndex = lines.indexOfFirst { line ->
            val match = TASK_PATTERN.matchEntire(line)
            match != null && match.groupValues[3].equals("x", ignoreCase = true)
        }

        if (firstCompletedIndex != -1) {
            val lastActiveBeforeCompleted = (firstCompletedIndex - 1 downTo 0).firstOrNull { idx ->
                val match = TASK_PATTERN.matchEntire(lines[idx])
                match != null && match.groupValues[3] == " "
            }

            if (lastActiveBeforeCompleted != null) {
                var insertAt = lastActiveBeforeCompleted + 1
                while (insertAt < firstCompletedIndex && isIndentedSubLine(lines[insertAt])) {
                    insertAt++
                }
                return insertAt
            }

            return firstCompletedIndex
        }

        // 4. Find last active task anywhere
        val lastActiveIndex = lines.indexOfLast { line ->
            val match = TASK_PATTERN.matchEntire(line)
            match != null && match.groupValues[3] == " "
        }

        if (lastActiveIndex != -1) {
            var insertAt = lastActiveIndex + 1
            while (insertAt < lines.size && isIndentedSubLine(lines[insertAt])) {
                insertAt++
            }
            return insertAt
        }

        // 5. Default fallback: end of file
        return lines.size
    }

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
            val bulletPrefix = if (bullet.isNotEmpty()) "$bullet " else ""
            val updatedLine = "$indent$bulletPrefix[$newStatus] $content"
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

            val bulletPrefix = if (bullet.isNotEmpty()) "$bullet " else ""
            val updatedLine = "$indent$bulletPrefix[$statusChar] $trimmed"
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
     * Inserts a new to-do checkbox item into the active task section of the markdown file.
     */
    @Synchronized
    fun addTask(context: Context, uri: Uri, taskTitle: String): Boolean {
        try {
            val trimmed = taskTitle.trim()
            if (trimmed.isEmpty()) return false

            val lines = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                    lines.add(line)
                }
            } ?: return false

            val insertIndex = findInsertionIndex(lines)
            val bullet = detectBulletStyle(lines)
            val newTaskLine = "$bullet [ ] $trimmed"

            lines.add(insertIndex, newTaskLine)

            // If the next line is a DONE header and not separated by a blank line, add a blank line
            if (insertIndex + 1 < lines.size && isDoneHeader(lines[insertIndex + 1])) {
                lines.add(insertIndex + 1, "")
            }

            return writeLines(context, uri, lines)
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
                    for (line in lines) {
                        writer.write(line)
                        writer.write("\n")
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
