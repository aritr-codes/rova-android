package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class StorageFormatTest {

    private val l = Locale.US

    @Test fun `formats bytes KB MB GB`() {
        assertEquals("0 B", StorageFormat.size(0, l))
        assertEquals("512 B", StorageFormat.size(512, l))
        assertEquals("1.0 KB", StorageFormat.size(1024, l))
        assertEquals("82.4 MB", StorageFormat.size(86_415_667, l))
        assertEquals("1.5 GB", StorageFormat.size(1_610_612_736, l))
    }

    @Test fun `day total sums sizes`() {
        assertEquals("3.0 KB", StorageFormat.dayTotal(listOf(1024L, 1024L, 1024L), l))
    }
}
