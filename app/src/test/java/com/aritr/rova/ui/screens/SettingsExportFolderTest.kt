package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2.1B review-fix — pure JVM tests for [sanitizeExportFolderName].
 *
 * The helper guards [com.aritr.rova.data.RovaSettings.exportFolderName]
 * from receiving invalid persisted state ahead of the Phase 5 export
 * pipeline consumer. Tests assert the contract documented in the
 * KDoc above the function.
 */
class SettingsExportFolderTest {

    @Test
    fun `plain name passes through unchanged`() {
        assertEquals("Rova", sanitizeExportFolderName("Rova"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("Rova", sanitizeExportFolderName("  Rova  "))
    }

    @Test
    fun `forward slash is removed`() {
        assertEquals("foobar", sanitizeExportFolderName("foo/bar"))
    }

    @Test
    fun `backslash is removed`() {
        assertEquals("foobar", sanitizeExportFolderName("foo\\bar"))
    }

    @Test
    fun `windows-invalid filename chars are stripped`() {
        assertEquals("abcd", sanitizeExportFolderName("a:b*c?d"))
    }

    @Test
    fun `quote bracket and pipe chars are stripped`() {
        assertEquals("abcd", sanitizeExportFolderName("a\"b<c>d|"))
    }

    @Test
    fun `ascii control characters are stripped`() {
        assertEquals("foobar", sanitizeExportFolderName("foobar"))
    }

    @Test
    fun `del control character 0x7F is stripped`() {
        assertEquals("foobar", sanitizeExportFolderName("foobar"))
    }

    @Test
    fun `whitespace only resolves to empty string`() {
        assertEquals("", sanitizeExportFolderName("   "))
    }

    @Test
    fun `only invalid chars resolves to empty string`() {
        assertEquals("", sanitizeExportFolderName("/\\:?\""))
    }

    @Test
    fun `single dot resolves to empty string`() {
        assertEquals("", sanitizeExportFolderName("."))
    }

    @Test
    fun `double dot resolves to empty string`() {
        assertEquals("", sanitizeExportFolderName(".."))
    }

    @Test
    fun `empty input resolves to empty string`() {
        assertEquals("", sanitizeExportFolderName(""))
    }

    @Test
    fun `triple dot is allowed since it is a normal filename`() {
        assertEquals("...", sanitizeExportFolderName("..."))
    }

    @Test
    fun `length is capped at 32 characters`() {
        val long = "a".repeat(40)
        val result = sanitizeExportFolderName(long)
        assertEquals(32, result.length)
        assertEquals("a".repeat(32), result)
    }

    @Test
    fun `length cap applies after sanitization`() {
        // 40 chars but every other one is a separator → 20 valid chars
        val mixed = "a/".repeat(20)
        assertEquals("a".repeat(20), sanitizeExportFolderName(mixed))
    }

    @Test
    fun `trailing whitespace from cap is re-trimmed`() {
        // 30 'a's + 4 spaces + 4 'b's. take(32) keeps "aaaa...aaa  " with
        // trailing spaces → final trim drops them.
        val input = "a".repeat(30) + "    " + "bbbb"
        assertEquals("a".repeat(30), sanitizeExportFolderName(input))
    }

    @Test
    fun `unicode letters pass through`() {
        assertEquals("Aritrāva", sanitizeExportFolderName("Aritrāva"))
    }

    @Test
    fun `dot in middle of name is preserved`() {
        assertEquals("my.folder", sanitizeExportFolderName("my.folder"))
    }
}
