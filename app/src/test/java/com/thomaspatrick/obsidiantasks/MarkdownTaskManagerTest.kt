package com.thomaspatrick.obsidiantasks

import com.thomaspatrick.obsidiantasks.data.MarkdownTaskManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTaskManagerTest {

    private val taskRegex = MarkdownTaskManager.TASK_PATTERN

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
    fun testTaskPatternMatchesCheckboxWithoutBullet() {
        val line = "[ ] Task without bullet"
        val match = taskRegex.matchEntire(line)
        assertTrue(match != null)
        assertEquals("", match!!.groupValues[1])
        assertEquals("", match.groupValues[2])
        assertEquals(" ", match.groupValues[3])
        assertEquals("Task without bullet", match.groupValues[4])
    }

    @Test
    fun testRegularMarkdownNotMatched() {
        assertFalse(taskRegex.matches("# Heading 1"))
        assertFalse(taskRegex.matches("- Normal bullet without checkbox"))
        assertFalse(taskRegex.matches("Just normal text [link](url)"))
    }

    @Test
    fun testIsDoneHeaderDetection() {
        assertTrue(MarkdownTaskManager.isDoneHeader("DONE"))
        assertTrue(MarkdownTaskManager.isDoneHeader("## DONE"))
        assertTrue(MarkdownTaskManager.isDoneHeader("### Completed"))
        assertTrue(MarkdownTaskManager.isDoneHeader("**DONE**"))
        assertTrue(MarkdownTaskManager.isDoneHeader("# Archive"))
        assertTrue(MarkdownTaskManager.isDoneHeader("Finished"))

        assertFalse(MarkdownTaskManager.isDoneHeader("To do"))
        assertFalse(MarkdownTaskManager.isDoneHeader("- [ ] Done shopping"))
        assertFalse(MarkdownTaskManager.isDoneHeader("- [x] Done shopping"))
    }

    @Test
    fun testInsertionBeforeDoneHeaderWithActiveTasks() {
        // Simulates user's exact notebook structure:
        // To do
        // [blank]
        // - [ ] Bread
        // [blank]
        // DONE
        // [blank]
        // - [x] Done 1
        // - [x] Done 2
        val lines = listOf(
            "To do",
            "",
            "- [ ] Bread",
            "",
            "DONE",
            "",
            "- [x] https://some-link",
            "- [x] Some finished task"
        )

        val insertIndex = MarkdownTaskManager.findInsertionIndex(lines)
        // New tasks should be placed at the VERY TOP of the active tasks (index 2, above "- [ ] Bread")
        assertEquals(2, insertIndex)

        val updated = lines.toMutableList()
        updated.add(insertIndex, "- [ ] Chicken 250gms")

        assertEquals("- [ ] Chicken 250gms", updated[2])
        assertEquals("- [ ] Bread", updated[3])
        assertEquals("", updated[4])
        assertEquals("DONE", updated[5])
    }

    @Test
    fun testInsertionBeforeDoneHeaderWithNoPriorTasks() {
        val lines = listOf(
            "To do",
            "",
            "DONE",
            "",
            "- [x] Done item"
        )

        val insertIndex = MarkdownTaskManager.findInsertionIndex(lines)
        // Should be placed after the blank line under "To do" (index 2)
        assertEquals(2, insertIndex)
    }

    @Test
    fun testInsertionInTodoSectionWithoutDoneHeader() {
        val lines = listOf(
            "# Tasks",
            "- [ ] Item A",
            "- [ ] Item B",
            "",
            "## Notes",
            "Some reference notes"
        )

        val insertIndex = MarkdownTaskManager.findInsertionIndex(lines)
        // Should be at the very top of active tasks, before Item A (index 1)
        assertEquals(1, insertIndex)
    }

    @Test
    fun testInsertionBeforeCompletedTasksWithoutHeaders() {
        val lines = listOf(
            "- [ ] First active",
            "- [x] Completed item 1",
            "- [x] Completed item 2"
        )

        val insertIndex = MarkdownTaskManager.findInsertionIndex(lines)
        // Should be at the very top of active tasks (index 0)
        assertEquals(0, insertIndex)
    }

    @Test
    fun testInsertionWithFrontmatter() {
        val lines = listOf(
            "---",
            "title: My Note",
            "---",
            "",
            "To do",
            "",
            "- [ ] Bread"
        )

        val insertIndex = MarkdownTaskManager.findInsertionIndex(lines)
        // Should be at index 6 (above "- [ ] Bread")
        assertEquals(6, insertIndex)
    }

    @Test
    fun testInsertionInEmptyDocument() {
        val lines = emptyList<String>()
        val insertIndex = MarkdownTaskManager.findInsertionIndex(lines)
        assertEquals(0, insertIndex)
    }

    @Test
    fun testDetectBulletStyle() {
        assertEquals("-", MarkdownTaskManager.detectBulletStyle(listOf("- [ ] Buy milk")))
        assertEquals("*", MarkdownTaskManager.detectBulletStyle(listOf("* [ ] Buy milk")))
        assertEquals("+", MarkdownTaskManager.detectBulletStyle(listOf("+ [ ] Buy milk")))
        assertEquals("-", MarkdownTaskManager.detectBulletStyle(emptyList()))
    }

    @Test
    fun testFindCheckedInsertionIndexWithBlankLineAfterDone() {
        val lines = listOf(
            "To do",
            "",
            "- [ ] Bread",
            "",
            "DONE",
            "",
            "- [x] Older completed 1",
            "- [x] Older completed 2"
        )

        val insertIndex = MarkdownTaskManager.findCheckedInsertionIndex(lines)
        // Line 4 is DONE, line 5 is blank. Target is line 6 (above "Older completed 1")
        assertEquals(6, insertIndex)

        val updated = lines.toMutableList()
        updated.add(insertIndex, "- [x] Just completed")

        assertEquals("DONE", updated[4])
        assertEquals("", updated[5])
        assertEquals("- [x] Just completed", updated[6])
        assertEquals("- [x] Older completed 1", updated[7])
    }

    @Test
    fun testFindCheckedInsertionIndexWithoutBlankLineAfterDone() {
        val lines = listOf(
            "To do",
            "- [ ] Bread",
            "DONE",
            "- [x] Older completed 1"
        )

        val insertIndex = MarkdownTaskManager.findCheckedInsertionIndex(lines)
        // Line 2 is DONE. Target is line 3 (directly below DONE, above Older completed 1)
        assertEquals(3, insertIndex)
    }

    @Test
    fun testFindCheckedInsertionIndexWithoutDoneHeader() {
        val lines = listOf(
            "- [ ] Task 1",
            "- [x] Completed 1",
            "- [x] Completed 2"
        )

        val insertIndex = MarkdownTaskManager.findCheckedInsertionIndex(lines)
        // Inserts before first completed item at index 1
        assertEquals(1, insertIndex)
    }
}
