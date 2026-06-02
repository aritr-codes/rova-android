package com.aritr.rova.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test fun str_carriesId() {
        val t = UiText.Str(42)
        assertEquals(42, (t as UiText.Str).id)
    }

    @Test fun strArgs_carriesIdAndArgs() {
        val t = UiText.StrArgs(7, listOf("a", 3))
        assertEquals(7, t.id)
        assertEquals(listOf<Any>("a", 3), t.args)
    }

    @Test fun plural_carriesIdQuantityArgs() {
        val t = UiText.Plural(9, quantity = 5, args = listOf(5))
        assertEquals(9, t.id)
        assertEquals(5, t.quantity)
        assertEquals(listOf<Any>(5), t.args)
    }

    @Test fun equality_byValue() {
        assertEquals(UiText.Str(1), UiText.Str(1))
        assertEquals(UiText.StrArgs(7, listOf("a")), UiText.StrArgs(7, listOf("a")))
        assertEquals(UiText.Plural(1, 2, listOf(2)), UiText.Plural(1, 2, listOf(2)))
    }
}
