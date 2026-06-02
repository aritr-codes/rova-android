package com.aritr.rova.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLocaleTest {

    // Supported list is injected so these tests are stable when a real
    // language is later appended to AppLocale.SUPPORTED_USER_LOCALES.
    private val withEs = listOf("es")

    @Test fun `localeTagFromStorage coerces null blank and unknown to system`() {
        assertNull(AppLocale.localeTagFromStorage(null, withEs))
        assertNull(AppLocale.localeTagFromStorage("", withEs))
        assertNull(AppLocale.localeTagFromStorage("   ", withEs))
        assertNull(AppLocale.localeTagFromStorage("fr", withEs)) // not in supported
    }

    @Test fun `localeTagFromStorage returns a supported tag unchanged`() {
        assertEquals("es", AppLocale.localeTagFromStorage("es", withEs))
    }

    @Test fun `with no supported locales every stored tag coerces to system`() {
        assertNull(AppLocale.localeTagFromStorage("es", emptyList()))
        assertNull(AppLocale.localeTagFromStorage("en", emptyList()))
    }

    @Test fun `languagePickerOptions is system-only when nothing is supported`() {
        val opts = AppLocale.languagePickerOptions(emptyList())
        assertEquals(1, opts.size)
        assertNull(opts.single().tag) // the system-default sentinel
    }

    @Test fun `languagePickerOptions prepends system before each supported tag`() {
        val opts = AppLocale.languagePickerOptions(withEs)
        assertEquals(listOf(null, "es"), opts.map { it.tag })
    }

    @Test fun `shouldShowLanguagePicker is false when only system is offered`() {
        assertFalse(AppLocale.shouldShowLanguagePicker(AppLocale.languagePickerOptions(emptyList())))
    }

    @Test fun `shouldShowLanguagePicker is true once a real language exists`() {
        assertTrue(AppLocale.shouldShowLanguagePicker(AppLocale.languagePickerOptions(withEs)))
    }

    @Test fun `shipping default keeps the picker hidden`() {
        // Guards the "no empty promise" rule: with the shipped SUPPORTED list,
        // the Language row must not render this slice.
        assertFalse(AppLocale.shouldShowLanguagePicker(AppLocale.languagePickerOptions()))
    }

    @Test fun `localeFor maps a tag to a Locale and null to system`() {
        assertNull(AppLocale.localeFor(null))
        assertEquals(Locale.forLanguageTag("es"), AppLocale.localeFor("es"))
    }
}
