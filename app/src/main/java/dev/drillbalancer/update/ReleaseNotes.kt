package dev.drillbalancer.update

/** A piece of a release's notes, as the app shows it: plain text, no links. */
sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock

    /** [marker]: a bullet, or a numbered item's number ("1."). */
    data class Bullet(val text: String, val marker: String = BULLET) : NoteBlock

    data class Paragraph(val text: String) : NoteBlock
}

private const val BULLET = "•"
private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val HEADING = Regex("""#{1,6}\s+(.*?)(?:\s+#+)?""")
private val ITEM = Regex("""\s*(?:[-*+]|(\d{1,9})[.)])\s+(.*)""")
private val CODE = Regex("`([^`]*)`")
private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val STARS = Regex("""(\*\*?)(\S(?:.*?\S)?)\1""")
private val UNDERSCORES = Regex("""(?<!\w)(__?)(\S(?:.*?\S)?)\1(?!\w)""")    // not inside NAMES_LIKE_THIS

/**
 * The Markdown docs/releases/vX.Y.Z.md uses, as blocks: headings, bullets, numbered items and
 * paragraphs. A line that is no new block continues the open one until a blank line, as on
 * GitHub. Links and images keep their text, emphasis its words, code its content.
 */
fun releaseNotes(markdown: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    var marker: String? = null              // of the open bullet; null for a paragraph
    var open: StringBuilder? = null

    fun close() {
        val text = plain(open?.toString() ?: return)
        blocks += marker?.let { NoteBlock.Bullet(text, it) } ?: NoteBlock.Paragraph(text)
        open = null
    }

    for (line in COMMENT.replace(markdown, "").lines().map(String::trimEnd)) {
        val heading = HEADING.matchEntire(line)
        val item = ITEM.matchEntire(line)
        when {
            line.isBlank() -> close()
            heading != null -> {
                close()
                blocks += NoteBlock.Heading(plain(heading.groupValues[1]))
            }
            item != null -> {
                close()
                marker = item.groupValues[1].takeIf { it.isNotEmpty() }?.let { "$it." } ?: BULLET
                open = StringBuilder(item.groupValues[2])
            }
            else -> open?.append(' ')?.append(line.trim()) ?: run {
                marker = null
                open = StringBuilder(line.trim())
            }
        }
    }
    close()
    return blocks
}

// Code spans first, so their content keeps every * and _.
private fun plain(text: String) = buildString {
    var at = 0
    for (code in CODE.findAll(text)) {
        append(prose(text.substring(at, code.range.first)))
        append(code.groupValues[1])
        at = code.range.last + 1
    }
    append(prose(text.substring(at)))
}.trim()

private fun prose(text: String) = UNDERSCORES.replace(STARS.replace(LINK.replace(text, "$1"), "$2"), "$2")
