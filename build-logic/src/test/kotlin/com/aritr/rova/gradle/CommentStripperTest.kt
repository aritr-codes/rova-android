package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentStripperTest {

    // Expectations use padStart/padEnd so the "everything before/after the code
    // becomes spaces" intent is expressed without hand-counting space runs.

    @Test
    fun blanksLineComment_keepsCodeBefore() {
        val src = "val x = 1 // c"
        // " // c" → spaces; total length preserved.
        assertEquals("val x = 1".padEnd(src.length), CommentStripper.strip(src))
    }

    @Test
    fun blanksBlockComment_inline_keepsTrailingCode() {
        // The F2 shape: /* … */ then real code on the same line. Everything
        // before "val x = 1" (comment + the space) becomes spaces.
        val src = "/* note */ val x = 1"
        assertEquals("val x = 1".padStart(src.length), CommentStripper.strip(src))
    }

    @Test
    fun blanksMultiLineBlock_preservesNewlines() {
        val src = "a\n/* line1\nline2 */\nb"
        val out = CommentStripper.strip(src)
        assertEquals(src.length, out.length)
        val lines = out.split("\n")
        assertEquals(4, lines.size)
        assertEquals("a", lines[0])
        assertTrue(lines[1].isBlank())  // "/* line1" → all spaces
        assertTrue(lines[2].isBlank())  // "line2 */" → all spaces
        assertEquals("b", lines[3])
    }

    @Test
    fun handlesNestedBlockComment() {
        // Kotlin allows nested block comments — the whole thing is one comment.
        val src = "/* /* */ */x"
        assertEquals("x".padStart(src.length), CommentStripper.strip(src))
    }

    @Test
    fun slashSlashInsideStringIsNotAComment() {
        val src = """val u = "http://x" + a"""
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun slashStarInsideStringIsNotAComment() {
        val src = """val s = "a /* not comment" + b"""
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun quoteInsideBlockCommentDoesNotStartString() {
        // The reference layered-regex strip corrupted this; the scanner must not.
        // Everything up to and including the */ (and the trailing space) is comment.
        val src = """/* he said "hi */ val x = 1"""
        assertEquals("val x = 1".padStart(src.length), CommentStripper.strip(src))
    }

    @Test
    fun charLiteralSlashIsNotAComment() {
        val src = "val c = '/' ; val d = 2"
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun rawStringKeepsCommentMarkers() {
        val src = "val r = \"\"\"a // b /* c */ d\"\"\" + e"
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun divisionOperatorIsKept() {
        val src = "val q = a / b"
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun crlfPreservedAcrossLineComment() {
        // \r must survive so reader().readLines() splits identically to the raw text.
        val src = "a // c\r\nb"
        val out = CommentStripper.strip(src)
        assertEquals(src.length, out.length)
        assertTrue(out.contains("\r\n"))
        assertEquals(src.reader().readLines().size, out.reader().readLines().size)
        assertEquals("b", out.reader().readLines().last())
    }

    @Test
    fun lengthPreservedAlways() {
        val src = "x /* y */ z // w\n\"q\" 'r' /* a\nb */ end"
        assertEquals(src.length, CommentStripper.strip(src).length)
    }

    @Test
    fun templateBodyKeptVerbatim_documentedOutOfScope() {
        // Out-of-scope (invariant-safe): template-internal comments are NOT
        // descended into; kept verbatim, matching pre-migration raw-line behavior.
        val src = "val s = \"\${ /* x */ value }\""
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun strayCloseBlockOutsideCommentIsCode() {
        // A stray */ outside any block comment is ordinary code — kept verbatim.
        val src = "val x = 1 */ "
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", CommentStripper.strip(""))
    }

    @Test
    fun backslashAtEofInsideStringDoesNotCrash() {
        // Backslash as the final char of an (unterminated) string literal:
        // the i+1 escape guard fails, no out-of-bounds write, length preserved.
        val src = "val s = \"abc\\"
        val out = CommentStripper.strip(src)
        assertEquals(src.length, out.length)
        assertEquals(src, out)
    }
}
