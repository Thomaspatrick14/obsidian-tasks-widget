package com.glassvault.widget.data

/**
 * Represents a parsed to-do item from an Obsidian markdown file.
 */
data class TaskItem(
    val id: Int,
    val lineIndex: Int,
    val originalLine: String,
    val title: String,
    val isCompleted: Boolean,
    val indentLevel: Int = 0,
    val tag: String? = null
)
