package dev.drillbalancer.update

import dev.drillbalancer.update.NoteBlock.Bullet
import dev.drillbalancer.update.NoteBlock.Heading
import dev.drillbalancer.update.NoteBlock.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun headingsBulletsAndParagraphs() {
        val notes = """
            ## Firmware

            - One level screen
            * The dot is smooth again

            Update the app first.
        """.trimIndent()
        assertEquals(
            listOf(Heading("Firmware"), Bullet("One level screen"), Bullet("The dot is smooth again"), Paragraph("Update the app first.")),
            releaseNotes(notes),
        )
    }

    @Test
    fun aParagraphsLinesJoinUntilABlankLine() {
        assertEquals(listOf(Paragraph("one two"), Paragraph("three")), releaseNotes("one\ntwo\n\nthree"))
    }

    @Test
    fun aWrappedLineContinuesItsBulletWithOrWithoutAnIndent() {
        for (notes in listOf("- Zero only\n  on the wall view", "- Zero only\non the wall view")) {
            assertEquals(notes, listOf(Bullet("Zero only on the wall view")), releaseNotes(notes))
        }
    }

    @Test
    fun numberedItemsKeepTheirNumbers() {
        assertEquals(listOf(Bullet("Flash the firmware", "1."), Bullet("Update the app", "2.")), releaseNotes("1. Flash the firmware\n2) Update the app"))
    }

    @Test
    fun aHeadingsClosingHashesAreDropped() {
        assertEquals(listOf(Heading("App")), releaseNotes("## App ##"))
    }

    @Test
    fun aHeadingOrBulletEndsAParagraph() {
        assertEquals(listOf(Paragraph("text"), Heading("App"), Bullet("x")), releaseNotes("text\n# App\n- x"))
    }

    @Test
    fun inlineMarkupIsDropped() {
        assertEquals(
            listOf(Bullet("The new error NO_ZERO, see the wiki, and bold and more, a note and this, screen")),
            releaseNotes(
                "- The new error `NO_ZERO`, see [the wiki](https://example.invalid/wiki), and **bold** and __more__, " +
                    "a *note* and _this_, ![screen](x.png)",
            ),
        )
    }

    @Test
    fun codeAndNamesKeepTheirUnderscores() {
        assertEquals(listOf(Paragraph("Fix __init__ and DB_ATT_ERR_NO_ZERO")), releaseNotes("Fix `__init__` and DB_ATT_ERR_NO_ZERO"))
    }

    @Test
    fun commentsAndWindowsLineEndsAreDropped() {
        assertEquals(listOf(Heading("App"), Bullet("x")), releaseNotes("<!-- written by hand -->\r\n# App\r\n- x\r\n"))
    }

    @Test
    fun blankNotesHaveNoBlocks() {
        assertEquals(emptyList<NoteBlock>(), releaseNotes(" \n\n"))
    }
}
