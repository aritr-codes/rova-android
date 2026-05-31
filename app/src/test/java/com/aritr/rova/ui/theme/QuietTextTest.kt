package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class QuietTextTest {

    private val onSurfaceVariant = Color(0xFF4C6175) // Ink80

    @Test
    fun `dark keeps the dimmed alpha`() {
        val c = quietTextColor(isDark = true, onSurfaceVariant = onSurfaceVariant, dimAlpha = 0.55f)
        assertEquals(0.55f, c.alpha, 0.001f)
    }

    @Test
    fun `light returns the solid color`() {
        val c = quietTextColor(isDark = false, onSurfaceVariant = onSurfaceVariant, dimAlpha = 0.55f)
        assertEquals(1.0f, c.alpha, 0.001f)
        assertEquals(onSurfaceVariant, c)
    }
}
