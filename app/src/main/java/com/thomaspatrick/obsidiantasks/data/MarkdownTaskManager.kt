package com.thomaspatrick.obsidiantasks.data

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

    // ------------------------------------------------------------------
    // Pure line operations. Shared by local-file mode and Nextcloud mode.
    // Each returns the new list, or null when the change does not apply.
    // ------------------------------------------------------------------

    fun parseLines(lines: List<String>, includeCompleted: Boolean = true): List<TaskItem> {
        val tasks = mutableListOf<TaskItem>()
        lines.forEachIndexed { index, line ->
            val match = TASK_PATTERN.matchEntire(line) ?: return@forEachIndexed
            val indent = match.groupValues[1]
            val rawContent = match.groupValues[4]
            val isCompleted = match.groupValues[3].equals("x", ignoreCase = true)
            if (!includeCompleted && isCompleted) return@forEachIndexed

            // Extract Obsidian tag if present (e.g., #todo, #urgent)
            val tag = TAG_PATTERN.find(rawContent)?.value
            val cleanTitle = if (tag != null) rawContent.replace(tag, "").trim() else rawContent.trim()

            tasks.add(
                TaskItem(
                    id = index,
                    lineIndex = index,
                    originalLine = line,
                    title = cleanTitle.ifEmpty { rawContent },
                    isCompleted = isCompleted,
                    indentLevel = (indent.length / 2).coerceAtMost(4),
                    tag = tag
                )
            )
        }
        return tasks
    }

    /**
     * Flips the checkbox on [lineIndex].
     * Checked: moves the task (and its indented sub-lines) to the top of the checked list (right below DONE).
     * Unchecked: moves it to the top of the active tasks (under To do).
     */
    fun toggleLines(source: List<String>, lineIndex: Int): List<String>? {
        if (lineIndex < 0 || lineIndex >= source.size) return null
        val match = TASK_PATTERN.matchEntire(source[lineIndex]) ?: return null
        val lines = source.toMutableList()

        val indent = match.groupValues[1]
        val bullet = match.groupValues[2]
        val isCurrentlyCompleted = match.groupValues[3].equals("x", ignoreCase = true)
        val content = match.groupValues[4]
        val bulletPrefix = if (bullet.isNotEmpty()) "$bullet " else ""
        val newStatus = if (isCurrentlyCompleted) " " else "x"

        val taskBlock = mutableListOf("$indent$bulletPrefix[$newStatus] $content")
        var subScan = lineIndex + 1
        while (subScan < lines.size && isIndentedSubLine(lines[subScan])) {
            taskBlock.add(lines[subScan])
            subScan++
        }
        repeat(taskBlock.size) { lines.removeAt(lineIndex) }

        // Clean up accidental duplicate blank lines caused by removal
        if (lineIndex > 0 && lineIndex < lines.size && lines[lineIndex - 1].isBlank() && lines[lineIndex].isBlank()) {
            lines.removeAt(lineIndex)
        }

        if (!isCurrentlyCompleted) {
            val doneHeaderIndex = lines.indexOfFirst { isDoneHeader(it) }
            if (doneHeaderIndex != -1 && doneHeaderIndex == lines.size - 1) {
                lines.add("")
                lines.addAll(taskBlock)
            } else {
                lines.addAll(findCheckedInsertionIndex(lines), taskBlock)
            }
        } else {
            val targetIndex = findInsertionIndex(lines)
            lines.addAll(targetIndex, taskBlock)
            val afterIndex = targetIndex + taskBlock.size
            if (afterIndex < lines.size && isDoneHeader(lines[afterIndex])) {
                lines.add(afterIndex, "")
            }
        }
        return lines
    }

    fun addLines(source: List<String>, taskTitle: String): List<String>? {
        val trimmed = taskTitle.trim()
        if (trimmed.isEmpty()) return null
        val lines = source.toMutableList()
        val insertIndex = findInsertionIndex(lines)
        lines.add(insertIndex, "${detectBulletStyle(lines)} [ ] $trimmed")
        if (insertIndex + 1 < lines.size && isDoneHeader(lines[insertIndex + 1])) {
            lines.add(insertIndex + 1, "")
        }
        return lines
    }

    fun renameLines(source: List<String>, lineIndex: Int, newTitle: String): List<String>? {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty() || lineIndex < 0 || lineIndex >= source.size) return null
        val match = TASK_PATTERN.matchEntire(source[lineIndex]) ?: return null
        val bullet = match.groupValues[2]
        val bulletPrefix = if (bullet.isNotEmpty()) "$bullet " else ""
        return source.toMutableList().also {
            it[lineIndex] = "${match.groupValues[1]}$bulletPrefix[${match.groupValues[3]}] $trimmed"
        }
    }

    fun deleteLines(source: List<String>, lineIndex: Int): List<String>? {
        if (lineIndex < 0 || lineIndex >= source.size || !TASK_PATTERN.matches(source[lineIndex])) return null
        return source.toMutableList().also { it.removeAt(lineIndex) }
    }

    // ------------------------------------------------------------------
    // Text <-> lines, keeping the file's line endings and trailing newline.
    // ------------------------------------------------------------------

    data class TextShape(val eol: String, val trailing: Boolean)

    fun splitText(text: String): Pair<List<String>, TextShape> {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val trailing = text.endsWith(eol)
        if (text.isEmpty()) return emptyList<String>() to TextShape(eol, false)
        val lines = text.split(Regex("\r?\n")).toMutableList()
        if (trailing) lines.removeAt(lines.size - 1)
        return lines to TextShape(eol, trailing)
    }

    fun joinText(lines: List<String>, shape: TextShape): String {
        val body = lines.joinToString(shape.eol)
        return if (shape.trailing || lines.isNotEmpty()) body + shape.eol else body
    }

    // ------------------------------------------------------------------
    // Local file (Storage Access Framework) mode. Used only when Nextcloud
    // sync is OFF. With Nextcloud ON the widget never writes the local file:
    // Obsidian's own Remotely Save plugin is the only thing that touches it.
    // ------------------------------------------------------------------

    fun readLines(context: Context, uri: Uri): Pair<List<String>, TextShape>? {
        val text = readRawContent(context, uri) ?: return null
        return splitText(text)
    }

    fun loadTasks(context: Context, uri: Uri, includeCompleted: Boolean = true): List<TaskItem> {
        val (lines, _) = readLines(context, uri) ?: return emptyList()
        return parseLines(lines, includeCompleted)
    }

    /**
     * Applies [op] to the file, but only if the line still reads [expectedLine]
     * (when given), so a stale widget tap never edits the wrong task.
     */
    @Synchronized
    fun mutateFile(
        context: Context,
        uri: Uri,
        lineIndex: Int,
        expectedLine: String?,
        op: (List<String>) -> List<String>?
    ): Boolean {
        return try {
            val (lines, shape) = readLines(context, uri) ?: return false
            if (expectedLine != null && lines.getOrNull(lineIndex) != expectedLine) return false
            val updated = op(lines) ?: return false
            writeRawContent(context, uri, joinText(updated, shape))
        } catch (e: Exception) {
            Log.e(TAG, "Error editing $uri", e)
            false
        }
    }

    fun toggleTask(context: Context, uri: Uri, lineIndex: Int, expectedLine: String? = null) =
        mutateFile(context, uri, lineIndex, expectedLine) { toggleLines(it, lineIndex) }

    fun updateTaskTitle(context: Context, uri: Uri, lineIndex: Int, newTitle: String, expectedLine: String? = null) =
        mutateFile(context, uri, lineIndex, expectedLine) { renameLines(it, lineIndex, newTitle) }

    fun deleteTask(context: Context, uri: Uri, lineIndex: Int, expectedLine: String? = null) =
        mutateFile(context, uri, lineIndex, expectedLine) { deleteLines(it, lineIndex) }

    fun addTask(context: Context, uri: Uri, taskTitle: String) =
        mutateFile(context, uri, 0, null) { addLines(it, taskTitle) }

    fun readRawContent(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading raw content from $uri", e)
            null
        }
    }

    @Synchronized
    fun writeRawContent(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw content to $uri", e)
            false
        }
    }
}
