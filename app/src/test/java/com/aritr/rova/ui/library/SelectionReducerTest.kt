package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionReducerTest {
    private val empty = SelectionState()

    @Test fun enterOnLongPress_activatesAndSelects() {
        val s = SelectionReducer.enter(empty, "a")
        assertTrue(s.active)
        assertEquals(setOf("a"), s.keys)
        assertEquals(1, s.count)
    }

    @Test fun toggle_addsThenRemoves_andDeactivatesWhenEmpty() {
        var s = SelectionReducer.enter(empty, "a")
        s = SelectionReducer.toggle(s, "b")
        assertEquals(setOf("a", "b"), s.keys)
        s = SelectionReducer.toggle(s, "a")
        assertEquals(setOf("b"), s.keys)
        s = SelectionReducer.toggle(s, "b")
        assertFalse(s.active)          // emptying selection exits select mode
        assertEquals(emptySet<String>(), s.keys)
    }

    @Test fun selectAllInGroup_unionsGroupKeys() {
        var s = SelectionReducer.enter(empty, "a")
        s = SelectionReducer.selectAll(s, listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), s.keys)
    }

    @Test fun selectAllInGroup_whenAllPresent_deselectsGroup() {
        var s = SelectionState(active = true, keys = setOf("a", "b", "c"))
        s = SelectionReducer.selectAll(s, listOf("a", "b", "c"))
        assertFalse(s.active)
        assertTrue(s.keys.isEmpty())
    }

    @Test fun reconcile_dropsMissingKeys_andExitsWhenEmpty() {
        val s = SelectionState(active = true, keys = setOf("a", "x"))
        val r = SelectionReducer.reconcile(s, setOf("a", "b"))
        assertEquals(setOf("a"), r.keys)
        assertTrue(r.active)
        val gone = SelectionReducer.reconcile(s, setOf("b"))
        assertFalse(gone.active)
        assertTrue(gone.keys.isEmpty())
    }

    @Test fun clear_resetsToInactive() {
        val s = SelectionState(active = true, keys = setOf("a"))
        assertEquals(SelectionState(), SelectionReducer.clear(s))
    }

    @Test fun removeAll_dropsKeys_keepsModeIfNonEmpty() {
        val s = SelectionState(active = true, keys = setOf("a", "b", "c"))
        val r = SelectionReducer.removeAll(s, setOf("a", "b"))
        assertEquals(setOf("c"), r.keys)
        assertTrue(r.active)
    }
}
