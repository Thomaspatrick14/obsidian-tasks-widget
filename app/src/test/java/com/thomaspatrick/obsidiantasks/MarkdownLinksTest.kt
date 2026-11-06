package com.thomaspatrick.obsidiantasks

import com.thomaspatrick.obsidiantasks.data.MarkdownLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinksTest {
    @Test fun markdownLinkShowsLabel() {
        val r = MarkdownLinks.render("[Zepto Bagrry's Rs.377](https://www.zepto.com/pn/bagrrys/pvid/37e4d1f9)")
        assertEquals("Zepto Bagrry's Rs.377", r.text)
        assertEquals("https://www.zepto.com/pn/bagrrys/pvid/37e4d1f9", r.firstUrl)
        assertEquals(listOf(0 until 21), r.linkRanges)
    }

    @Test fun textAroundLinkIsKept() {
        val r = MarkdownLinks.render("Buy [milk](https://a.b/m) and [eggs](https://a.b/e) today")
        assertEquals("Buy milk and eggs today", r.text)
        assertEquals("https://a.b/m", r.firstUrl)
        assertEquals(listOf(4 until 8, 13 until 17), r.linkRanges)
    }

    @Test fun wikilinks() {
        assertEquals("alias", MarkdownLinks.render("[[Some note|alias]]").text)
        val r = MarkdownLinks.render("See [[Some note]]")
        assertEquals("See Some note", r.text)
        assertEquals("obsidian://open?file=Some%20note", r.firstUrl)
    }

    @Test fun plainTextAndBareUrls() {
        val plain = MarkdownLinks.render("Rajma")
        assertEquals("Rajma", plain.text); assertNull(plain.firstUrl)
        assertEquals("https://x.y/z", MarkdownLinks.render("https://x.y/z").firstUrl)
    }

    @Test fun onlySafeSchemesOpen() {
        assertTrue(MarkdownLinks.isOpenable("https://x"))
        assertTrue(MarkdownLinks.isOpenable("obsidian://open?file=a"))
        assertFalse(MarkdownLinks.isOpenable("intent://evil"))
        assertFalse(MarkdownLinks.isOpenable("file:///sdcard/x"))
    }
}
