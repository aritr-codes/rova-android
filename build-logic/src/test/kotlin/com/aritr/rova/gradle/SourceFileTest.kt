package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFileTest {

    private fun fromText(text: String) =
        SourceFile("a/B.kt", text.reader().readLines(), text)

    @Test
    fun strippedLinesAlignWithLines_noTrailingNewline() {
        val text = "val a = 1 // x\n/* b */ val c = 2"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
        assertEquals("val a = 1".padEnd("val a = 1 // x".length), sf.strippedLines[0])
        assertEquals("val c = 2".padStart("/* b */ val c = 2".length), sf.strippedLines[1])
    }

    @Test
    fun strippedLinesAlignWithLines_trailingNewline() {
        val text = "val a = 1\nval b = 2\n"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
    }

    @Test
    fun strippedLinesAlignWithLines_crlf() {
        val text = "val a = 1 // c\r\nval b = 2"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
    }

    @Test
    fun strippedLinesAlignWithLines_multiLineBlock() {
        val text = "a\n/* c1\nc2\nc3 */\nb"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
        assertEquals("a", sf.strippedLines[0])
        assertEquals("b", sf.strippedLines[4])
    }

    @Test
    fun strippedTextEqualsStripOfText() {
        val text = "x /* y */ z"
        assertEquals(CommentStripper.strip(text), fromText(text).strippedText)
    }
}
