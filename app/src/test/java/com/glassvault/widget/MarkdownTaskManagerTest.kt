package com.glassvault.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTaskManagerTest {

    private val taskRegex = Regex("""^(\s*)([-*+]|\d+\.)\s+\[([ xX])\]\s*(.*)$""")

    @Test
    fun testTaskPatternMatchesStandardCheckbox() {
        val line = "- [ ] Buy groceries"
        val match = taskRegex.matchEntire(line)
        assertTrue(match != null)
        assertEquals("", match!!.groupValues[1])
        assertEquals("-", match.groupValues[2])
        assertEquals(" ", match.groupValues[3])
        assertEquals("Buy groceries", match.groupValues[4])
    }

    @Test
    fun testTaskPatternMatchesCompletedCheckbox() {
        val line = "- [x] Finish Android widget"
        val match = taskRegex.matchEntire(line)
        assertTrue(match != null)
        assertEquals("x", match!!.groupValues[3])
        assertEquals("Finish Android widget", match.groupValues[4])
    }

    @Test
    fun testTaskPatternMatchesCapitalX() {
        val line = "* [X] Capital X task"
        val match = taskRegex.matchEntire(line)
        assertTrue(match != null)
        assertEquals("X", match!!.groupValues[3])
    }

    @Test
    fun testTaskPatternMatchesIndentedSubtask() {
        val line = "    + [ ] Deeply nested subtask"
        val match = taskRegex.matchEntire(line)
        assertTrue(match != null)
        assertEquals("    ", match!!.groupValues[1])
        assertEquals("+", match.groupValues[2])
        assertEquals("Deeply nested subtask", match.groupValues[4])
    }

    @Test
    fun testTaskPatternMatchesNumberedCheckbox() {
        val line = "1. [ ] Step one"
        val match = taskRegex.matchEntire(line)
        assertTrue(match != null)
        assertEquals("1.", match!!.groupValues[2])
    }

    @Test
    fun testRegularMarkdownNotMatched() {
        assertFalse(taskRegex.matches("# Heading 1"))
        assertFalse(taskRegex.matches("- Normal bullet without checkbox"))
        assertFalse(taskRegex.matches("Just normal text [link](url)"))
    }
}
