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
     * New tasks are placed at the VERY TOP of the active/uncompleted tasks list.
     */
    fun findInsertionIndex(lines: List<String>): Int {
        if (lines.isEmpty()) return 0

        // Skip YAML frontmatter if present (--- ... ---)
        var startIndex = 0
        if (lines[0].trim() == "---") {
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    startIndex = i + 1
                    break
                }
            }
        }

        // 1. Check for a TODO / Tasks header
        val todoHeaderIndex = (startIndex until lines.size).firstOrNull { isTodoHeader(lines[it]) }

        if (todoHeaderIndex != null) {
            // Find where this todo section ends (next header or DONE header or EOF)
            var sectionEnd = lines.size
            for (i in (todoHeaderIndex + 1) until lines.size) {
                val line = lines[i]
                if (isDoneHeader(line)) {
                    sectionEnd = i
                    break
                }
                val trimmed = line.trim()
                if (trimmed.startsWith("#") && !TASK_PATTERN.matches(trimmed)) {
                    sectionEnd = i
                    break
                }
            }

            // Find the FIRST uncompleted task in this section
            val firstActiveInSection = (todoHeaderIndex + 1 until sectionEnd).firstOrNull { idx ->
                val match = TASK_PATTERN.matchEntire(lines[idx])
                match != null && match.groupValues[3] == " "
            }

            if (firstActiveInSection != null) {
                // Insert at the very top of the active tasks!
                return firstActiveInSection
            }

            // If no active tasks exist yet, place right after the TODO header (skipping one blank line if present)
            var insertAt = todoHeaderIndex + 1
            if (insertAt < sectionEnd && lines[insertAt].isBlank()) {
                insertAt++
            }
            return insertAt
        }

        // 2. No TODO header. Check if there is an existing active task anywhere after startIndex
        val firstActiveIndex = (startIndex until lines.size).firstOrNull { idx ->
            val match = TASK_PATTERN.matchEntire(lines[idx])
            match != null && match.groupValues[3] == " "
        }

        if (firstActiveIndex != null) {
            // Insert at the very top of the active tasks!
            return firstActiveIndex
        }

        // 3. No active tasks found. Check if a DONE header exists
        val doneHeaderIndex = (startIndex until lines.size).firstOrNull { isDoneHeader(lines[it]) }
        if (doneHeaderIndex != null) {
            var insertAt = doneHeaderIndex
            while (insertAt > startIndex && lines[insertAt - 1].isBlank()) {
                insertAt--
            }
            return insertAt
        }

        // 4. Check if there are completed tasks
        val firstCompletedIndex = (startIndex until lines.size).firstOrNull { idx ->
            val match = TASK_PATTERN.matchEntire(lines[idx])
            match != null && match.groupValues[3].equals("x", ignoreCase = true)
        }

        if (firstCompletedIndex != null) {
            return firstCompletedIndex
        }

        // 5. Default fallback: insert at startIndex (top of document below frontmatter)
        var insertAt = startIndex
        while (insertAt < lines.size && lines[insertAt].isBlank()) {
            insertAt++
        }
        return insertAt
    }

    /**
     * Determines the line index where a newly checked (completed) task should be inserted.
     * Places it at the VERY TOP of the checked list (right below the DONE / Completed header).
     */
    fun findCheckedInsertionIndex(lines: List<String>): Int {
        if (lines.isEmpty()) return 0

        // 1. Look for DONE / Completed header
        val doneHeaderIndex = lines.indexOfFirst { isDoneHeader(it) }
        if (doneHeaderIndex != -1) {
            var insertAt = doneHeaderIndex + 1
            // If there is an empty line right after DONE, put after the empty line
            if (insertAt < lines.size && lines[insertAt].isBlank()) {
                insertAt++
            }
            return insertAt
        }

        // 2. If no DONE header, look for the first completed task
        val firstCompletedIndex = lines.indexOfFirst { line ->
            val match = TASK_PATTERN.matchEntire(line)
            match != null && match.groupValues[3].equals("x", ignoreCase = true)
        }
        if (firstCompletedIndex != -1) {
            return firstCompletedIndex
        }

        // 3. Otherwise append to the end of the document
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
     * When checked: moves the task to the top of the checked list (right below DONE).
     * When unchecked: moves the task to the top of the active tasks (under To do).
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

            val isCurrentlyCompleted = statusChar.equals("x", ignoreCase = true)
            val newStatus = if (isCurrentlyCompleted) " " else "x"
            val bulletPrefix = if (bullet.isNotEmpty()) "$bullet " else ""
            val updatedLine = "$indent$bulletPrefix[$newStatus] $content"

            // Gather task line and any indented sub-notes
            val taskBlock = mutableListOf<String>()
            taskBlock.add(updatedLine)
            var subScan = lineIndex + 1
            while (subScan < lines.size && isIndentedSubLine(lines[subScan])) {
                taskBlock.add(lines[subScan])
                subScan++
            }

            // Remove the old task block
            repeat(taskBlock.size) {
                lines.removeAt(lineIndex)
            }

            // Clean up accidental duplicate blank lines caused by removal
            if (lineIndex > 0 && lineIndex < lines.size && lines[lineIndex - 1].isBlank() && lines[lineIndex].isBlank()) {
                lines.removeAt(lineIndex)
            }

            if (!isCurrentlyCompleted) {
                // Task was checked: move to the top of the checked list (right below DONE)
                val doneHeaderIndex = lines.indexOfFirst { isDoneHeader(it) }
                if (doneHeaderIndex != -1 && doneHeaderIndex == lines.size - 1) {
                    // DONE is the very last line; add a blank line after it before inserting
                    lines.add("")
                    lines.addAll(taskBlock)
                } else {
                    val targetIndex = findCheckedInsertionIndex(lines)
                    lines.addAll(targetIndex, taskBlock)
                }
            } else {
                // Task was unchecked: move to the top of the active tasks (under To do)
                val targetIndex = findInsertionIndex(lines)
                lines.addAll(targetIndex, taskBlock)

                // If placed directly against a DONE header without spacing, insert blank line
                val afterIndex = targetIndex + taskBlock.size
                if (afterIndex < lines.size && isDoneHeader(lines[afterIndex])) {
                    lines.add(afterIndex, "")
                }
            }

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
