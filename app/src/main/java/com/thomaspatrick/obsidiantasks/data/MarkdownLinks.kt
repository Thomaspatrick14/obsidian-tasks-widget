package com.thomaspatrick.obsidiantasks.data

import java.net.URLEncoder

/**
 * Turns task text with markdown links into what the widget shows:
 *   "[Zepto Bagrry's Rs.377](https://…)"  ->  "Zepto Bagrry's Rs.377"
 *   "[[Some note|alias]]" -> "alias",  "[[Some note]]" -> "Some note"
 *   bare https://… URLs are kept as text.
 * Same rules as the desktop widget.
 */
object MarkdownLinks {

    private val LINK = Regex("""\[([^\]]+)\]\((\S+?)\)|\[\[([^\]|]+)(?:\|([^\]]+))?\]\]|(https?://\S+)""")

    data class Rendered(
        val text: String,
        val linkRanges: List<IntRange>,   // where the link labels are in [text], for styling
        val firstUrl: String?             // what the "open link" button opens
    )

    fun render(source: String): Rendered {
        val out = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var firstUrl: String? = null
        var last = 0
        for (m in LINK.findAll(source)) {
            out.append(source, last, m.range.first)
            val (label, url) = when {
                m.groups[1] != null -> m.groupValues[1] to m.groupValues[2]
                m.groups[3] != null -> (m.groups[4]?.value ?: m.groupValues[3]) to
                    "obsidian://open?file=" + URLEncoder.encode(m.groupValues[3], "UTF-8").replace("+", "%20")
                else -> m.groupValues[5] to m.groupValues[5]
            }
            val start = out.length
            out.append(label)
            ranges.add(start until out.length)
            if (firstUrl == null) firstUrl = url
            last = m.range.last + 1
        }
        out.append(source, last, source.length)
        return Rendered(out.toString(), ranges, firstUrl)
    }

    /** Only schemes that are safe to hand to an ACTION_VIEW intent. */
    fun isOpenable(url: String) = Regex("^(https?|obsidian|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(url)
}
