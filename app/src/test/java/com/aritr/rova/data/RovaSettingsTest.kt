package com.aritr.rova.data

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 schema reconciliation — covers all 13 RovaSettings keys
 * (10 pre-existing + 3 UI-pending) plus a legacy-blob round-trip that
 * proves a SharedPreferences blob containing only the 10 pre-existing
 * keys reads documented defaults for the 3 new keys.
 *
 * Pure-JVM (no Robolectric, no mocking framework) per the project's
 * extract-pure-logic test policy: a self-contained `FakeSharedPreferences`
 * + `ContextWrapper(null)` stub is enough because `RovaSettings` only
 * calls `Context#getSharedPreferences`.
 */
class RovaSettingsTest {

    // ─── Documented defaults: 10 pre-existing keys ────────────────

    @Test fun `durationSeconds default is 10`() {
        assertEquals(10, settings().durationSeconds)
    }

    @Test fun `intervalMinutes default is 1`() {
        assertEquals(1, settings().intervalMinutes)
    }

    @Test fun `resolution default is QualityPresets DEFAULT`() {
        assertEquals(QualityPresets.DEFAULT, settings().resolution)
    }

    @Test fun `loopCount default is 10`() {
        assertEquals(10, settings().loopCount)
    }

    @Test fun `enableBeeps default is true`() {
        assertTrue(settings().enableBeeps)
    }

    @Test fun `vibrateAlerts default is true`() {
        assertTrue(settings().vibrateAlerts)
    }

    @Test fun `keepScreenOn default is false`() {
        assertFalse(settings().keepScreenOn)
    }

    @Test fun `customPresetsJson default is empty array`() {
        assertEquals("[]", settings().customPresetsJson)
    }

    @Test fun `autoDeleteEnabled default is false`() {
        assertFalse(settings().autoDeleteEnabled)
    }

    @Test fun `autoDeleteKeepLatest default is 10`() {
        assertEquals(10, settings().autoDeleteKeepLatest)
    }

    // ─── Documented defaults: 3 UI-pending keys ───────────────────

    @Test fun `onboardingCompleted default is false`() {
        assertFalse(settings().onboardingCompleted)
    }

    @Test fun `autoExportEnabled default is true`() {
        assertTrue(settings().autoExportEnabled)
    }

    @Test fun `exportFolderName default is empty`() {
        assertEquals("", settings().exportFolderName)
    }

    // ─── Round-trip: 10 pre-existing keys ─────────────────────────

    @Test fun `durationSeconds round-trip`() {
        val s = settings(); s.durationSeconds = 30
        assertEquals(30, s.durationSeconds)
    }

    @Test fun `intervalMinutes round-trip`() {
        val s = settings(); s.intervalMinutes = 5
        assertEquals(5, s.intervalMinutes)
    }

    @Test fun `resolution round-trip`() {
        val s = settings(); s.resolution = QualityPresets.HD
        assertEquals(QualityPresets.HD, s.resolution)
    }

    @Test fun `loopCount round-trip including continuous sentinel`() {
        val s = settings()
        s.loopCount = 100
        assertEquals(100, s.loopCount)
        s.loopCount = -1
        assertEquals(-1, s.loopCount)
    }

    @Test fun `enableBeeps round-trip`() {
        val s = settings(); s.enableBeeps = false
        assertFalse(s.enableBeeps)
    }

    @Test fun `vibrateAlerts round-trip`() {
        val s = settings(); s.vibrateAlerts = false
        assertFalse(s.vibrateAlerts)
    }

    @Test fun `keepScreenOn round-trip`() {
        val s = settings(); s.keepScreenOn = true
        assertTrue(s.keepScreenOn)
    }

    @Test fun `customPresetsJson round-trip`() {
        val s = settings(); s.customPresetsJson = """[{"name":"Drill"}]"""
        assertEquals("""[{"name":"Drill"}]""", s.customPresetsJson)
    }

    @Test fun `autoDeleteEnabled round-trip`() {
        val s = settings(); s.autoDeleteEnabled = true
        assertTrue(s.autoDeleteEnabled)
    }

    @Test fun `autoDeleteKeepLatest round-trip`() {
        val s = settings(); s.autoDeleteKeepLatest = 25
        assertEquals(25, s.autoDeleteKeepLatest)
    }

    // ─── Round-trip: 3 UI-pending keys ────────────────────────────

    @Test fun `onboardingCompleted round-trip`() {
        val s = settings(); s.onboardingCompleted = true
        assertTrue(s.onboardingCompleted)
    }

    @Test fun `autoExportEnabled round-trip`() {
        val s = settings(); s.autoExportEnabled = false
        assertFalse(s.autoExportEnabled)
    }

    @Test fun `exportFolderName round-trip`() {
        val s = settings(); s.exportFolderName = "Rova"
        assertEquals("Rova", s.exportFolderName)
    }

    // ─── Legacy blob: only 10 pre-existing keys present ───────────

    @Test fun `legacy blob with only 10 pre-existing keys reads documented defaults for 3 new keys`() {
        val legacy = HashMap<String, Any?>().apply {
            put("duration", 30)
            put("interval", 5)
            put("resolution", QualityPresets.HD)
            put("loop_count", 100)
            put("enable_beeps", false)
            put("vibrate_alerts", false)
            put("keep_screen_on", true)
            put("custom_presets", """[{"name":"Drill"}]""")
            put("auto_delete_enabled", true)
            put("auto_delete_keep_latest", 25)
        }
        val s = settings(legacy)

        // 10 pre-existing keys round-trip from the blob
        assertEquals(30, s.durationSeconds)
        assertEquals(5, s.intervalMinutes)
        assertEquals(QualityPresets.HD, s.resolution)
        assertEquals(100, s.loopCount)
        assertFalse(s.enableBeeps)
        assertFalse(s.vibrateAlerts)
        assertTrue(s.keepScreenOn)
        assertEquals("""[{"name":"Drill"}]""", s.customPresetsJson)
        assertTrue(s.autoDeleteEnabled)
        assertEquals(25, s.autoDeleteKeepLatest)

        // 3 new keys fall back to documented defaults
        assertFalse(s.onboardingCompleted)
        assertTrue(s.autoExportEnabled)
        assertEquals("", s.exportFolderName)
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private fun settings(initial: Map<String, Any?> = emptyMap()): RovaSettings {
        val prefs = FakeSharedPreferences(HashMap(initial))
        return RovaSettings(FakeContext(prefs))
    }
}

private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

private class FakeSharedPreferences(
    private val store: MutableMap<String, Any?>
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = HashMap(store)

    override fun getString(key: String?, defValue: String?): String? =
        if (key != null && store.containsKey(key)) store[key] as? String else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (key != null && store.containsKey(key)) (store[key] as? Set<String>)?.toMutableSet() else defValues

    override fun getInt(key: String?, defValue: Int): Int =
        if (key != null && store.containsKey(key)) (store[key] as? Int) ?: defValue else defValue

    override fun getLong(key: String?, defValue: Long): Long =
        if (key != null && store.containsKey(key)) (store[key] as? Long) ?: defValue else defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        if (key != null && store.containsKey(key)) (store[key] as? Float) ?: defValue else defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (key != null && store.containsKey(key)) (store[key] as? Boolean) ?: defValue else defValue

    override fun contains(key: String?): Boolean = key != null && store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(store)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

private class FakeEditor(
    private val store: MutableMap<String, Any?>
) : SharedPreferences.Editor {

    private val pending = HashMap<String, Any?>()
    private val removed = HashSet<String>()
    private var doClear = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor =
        also { if (key != null) pending[key] = value }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?
    ): SharedPreferences.Editor = also { if (key != null) pending[key] = values }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
        also { if (key != null) pending[key] = value }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
        also { if (key != null) pending[key] = value }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
        also { if (key != null) pending[key] = value }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
        also { if (key != null) pending[key] = value }

    override fun remove(key: String?): SharedPreferences.Editor =
        also { if (key != null) removed.add(key) }

    override fun clear(): SharedPreferences.Editor = also { doClear = true }

    override fun commit(): Boolean {
        applyChanges()
        return true
    }

    override fun apply() {
        applyChanges()
    }

    private fun applyChanges() {
        if (doClear) store.clear()
        removed.forEach { store.remove(it) }
        store.putAll(pending)
    }
}
