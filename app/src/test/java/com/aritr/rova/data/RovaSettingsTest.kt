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

    // ─── CaptureTopology coercion ──────────────────────────────────

    @Test fun `captureTopology default is Single`() {
        assertEquals("Single", settings().captureTopology)
    }

    @Test fun `captureTopology persists DualShot`() {
        val s = settings(); s.captureTopology = "DualShot"
        assertEquals("DualShot", s.captureTopology)
    }

    @Test fun `captureTopology coerces unknown stored value to Single`() {
        val s = settingsWithRuntime(runtime = hashMapOf<String, Any?>("capture_topology" to "P + L"))
        assertEquals("Single", s.captureTopology)
    }

    @Test fun `orientationPolicy default is FollowDevice and persists Lock`() {
        val s = settings()
        assertEquals("FollowDevice", s.orientationPolicy)
        s.orientationPolicy = "Lock"; s.orientationLockRotation = 1
        assertEquals("Lock", s.orientationPolicy)
        assertEquals(1, s.orientationLockRotation)
    }

    // ─── Legacy-mode migration (ADR-0029 §6) ───────────────────────

    @Test fun `legacy runtime Portrait migrates to Single plus PortraitLock once`() {
        val runtime = hashMapOf<String, Any?>("mode" to "Portrait")
        val s = settingsWithRuntime(runtime = runtime)
        assertEquals("Single", s.captureTopology)
        assertEquals("Lock", s.orientationPolicy)
        assertEquals(0, s.orientationLockRotation)
        assertEquals("legacy key left in place one release", "Portrait", runtime["mode"])
    }

    @Test fun `legacy runtime Landscape migrates to Single plus LandscapeLock`() {
        val runtime = hashMapOf<String, Any?>("mode" to "Landscape")
        val s = settingsWithRuntime(runtime = runtime)
        assertEquals("Single", s.captureTopology)
        assertEquals("Lock", s.orientationPolicy)
        assertEquals(1, s.orientationLockRotation)
    }

    @Test fun `legacy runtime PortraitLandscape migrates to DualShot FollowDevice`() {
        val runtime = hashMapOf<String, Any?>("mode" to "PortraitLandscape")
        val s = settingsWithRuntime(runtime = runtime)
        assertEquals("DualShot", s.captureTopology)
        assertEquals("FollowDevice", s.orientationPolicy)
    }

    @Test fun `migration does not rerun once capture_topology exists`() {
        val runtime = hashMapOf<String, Any?>("mode" to "Portrait")
        val s1 = settingsWithRuntime(runtime = runtime)
        s1.captureTopology // trigger migration
        s1.captureTopology = "DualShot"
        val s2 = settingsWithRuntime(runtime = runtime)
        assertEquals("user choice survives reconstruction", "DualShot", s2.captureTopology)
    }

    // ─── captureTopology setter writes to runtime prefs not main prefs ─

    @Test fun `captureTopology setter writes to runtime prefs not main prefs`() {
        val runtime = HashMap<String, Any?>()
        val main = HashMap<String, Any?>()
        val s = settingsWithRuntime(main = main, runtime = runtime)
        s.captureTopology = "DualShot"
        assertEquals("DualShot", runtime["capture_topology"])
        assertFalse("main prefs must not store capture_topology", main.containsKey("capture_topology"))
    }

    // ─── Main-prefs legacy-key cleanup (backup-restore defense) ─────
    //
    // Pre-mode-split installs stored `mode` in main prefs (rova_settings.xml),
    // which IS backed up by Android Auto Backup. After a reinstall the restored
    // backup brought back `mode=PortraitLandscape` and the app opened in
    // DualShot — the bug this whole patch chain exists to kill. The init block
    // deletes the legacy key from main prefs without copying it to runtime;
    // runtime is backup-excluded so reinstall finds it empty and the getters
    // fall through to documented defaults (Single / FollowDevice / -1).
    //
    // We DELIBERATELY do not migrate the legacy value here. Auto Backup
    // snapshots run on a schedule (~24 h, idle + charging) so a user who
    // installs this patch, sets a topology, and then reinstalls before the next
    // snapshot would have their PRE-PATCH backup restored with a stale
    // `mode=PortraitLandscape` in main prefs and no marker. Migrating would
    // faithfully preserve that stale P+L value and defeat the fix.

    @Test fun `init deletes legacy mode key from main prefs and does NOT copy to runtime`() {
        val main = hashMapOf<String, Any?>("mode" to "PortraitLandscape")
        val runtime = HashMap<String, Any?>()
        val s = settingsWithRuntime(main = main, runtime = runtime)
        assertFalse("legacy mode key must be removed from main prefs", main.containsKey("mode"))
        assertFalse("legacy value must NOT migrate into runtime prefs", runtime.containsKey("mode"))
        assertEquals("topology must default to Single after cleanup", "Single", s.captureTopology)
    }

    @Test fun `init is idempotent when no legacy mode key is present`() {
        val main = HashMap<String, Any?>()
        val runtime = HashMap<String, Any?>()
        settingsWithRuntime(main = main, runtime = runtime)
        assertFalse(main.containsKey("mode"))
        assertFalse(runtime.containsKey("mode"))
    }

    @Test fun `reinstall-after-backup defaults to Single`() {
        // Reinstall path: Auto Backup restored main prefs (may still have a
        // stale `mode=PortraitLandscape` from a pre-patch snapshot). Runtime
        // prefs is empty because it's backup-excluded. Init must wipe the
        // legacy key from main; runtime stays clean so the getter defaults Single.
        val main = hashMapOf<String, Any?>("mode" to "PortraitLandscape")
        val runtime = HashMap<String, Any?>()
        val s = settingsWithRuntime(main = main, runtime = runtime)
        assertEquals("Single", s.captureTopology)
        assertFalse(main.containsKey("mode"))
        assertFalse("runtime must not gain a legacy mode key", runtime.containsKey("mode"))
    }

    @Test fun `second construction does not clobber a user-set topology`() {
        val main = HashMap<String, Any?>()
        val runtime = HashMap<String, Any?>()
        val s1 = settingsWithRuntime(main = main, runtime = runtime)
        s1.captureTopology = "DualShot"
        val s2 = settingsWithRuntime(main = main, runtime = runtime)
        assertEquals("DualShot", s2.captureTopology)
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

        // new keys fall back to documented defaults
        assertFalse(s.onboardingCompleted)
        assertTrue(s.autoExportEnabled)
    }

    // ─── snoozedWarningIds (Phase 4.1c) ───────────────────────────

    @Test fun `snoozedWarningIds default is empty set`() {
        assertEquals(emptySet<String>(), settings().snoozedWarningIds)
    }

    @Test fun `snoozedWarningIds round-trips a 2-id set`() {
        val s = settings()
        s.snoozedWarningIds = setOf("NOTIFICATIONS_DENIED", "BATTERY_OPTIMIZATION_ON")
        assertEquals(
            setOf("NOTIFICATIONS_DENIED", "BATTERY_OPTIMIZATION_ON"),
            s.snoozedWarningIds,
        )
    }

    @Test fun `snoozedWarningIds setter replaces, does not merge`() {
        val s = settings()
        s.snoozedWarningIds = setOf("A", "B")
        s.snoozedWarningIds = setOf("C")
        assertEquals(setOf("C"), s.snoozedWarningIds)
    }

    // ─── dismissedAutoStopEchoIds (Phase 4 Slice 2) ─────────────────

    @Test fun `dismissedAutoStopEchoIds default is empty set`() {
        assertEquals(emptySet<String>(), settings().dismissedAutoStopEchoIds)
    }

    @Test fun `dismissedAutoStopEchoIds round-trips a 2-id set`() {
        val s = settings()
        s.dismissedAutoStopEchoIds = setOf("session-a", "session-b")
        assertEquals(setOf("session-a", "session-b"), s.dismissedAutoStopEchoIds)
    }

    @Test fun `dismissedAutoStopEchoIds setter replaces, does not merge`() {
        val s = settings()
        s.dismissedAutoStopEchoIds = setOf("a", "b")
        s.dismissedAutoStopEchoIds = setOf("c")
        assertEquals(setOf("c"), s.dismissedAutoStopEchoIds)
    }

    // ─── Helpers ──────────────────────────────────────────────────

    /**
     * Single-store helper for legacy tests that only touch keys in the
     * main rova_settings file. Builds an empty runtime store under the
     * RUNTIME_PREFS_NAME bucket so the mode getter defaults to Portrait
     * unless a test routes through [settingsWithRuntime] instead.
     */
    private fun settings(initial: Map<String, Any?> = emptyMap()): RovaSettings {
        val main = HashMap<String, Any?>(initial)
        return settingsWithRuntime(main = main, runtime = HashMap())
    }

    /**
     * Multi-store helper introduced for the mode-split migration tests.
     * Caller passes mutable maps so tests can assert post-migration state.
     */
    private fun settingsWithRuntime(
        main: MutableMap<String, Any?> = HashMap(),
        runtime: MutableMap<String, Any?> = HashMap(),
    ): RovaSettings {
        val mainPrefs = FakeSharedPreferences(main)
        val runtimePrefs = FakeSharedPreferences(runtime)
        return RovaSettings(
            FakeContext(
                mapOf(
                    "rova_settings" to mainPrefs,
                    RovaSettings.RUNTIME_PREFS_NAME to runtimePrefs,
                )
            )
        )
    }
}

private class FakeContext(
    private val byName: Map<String, SharedPreferences>,
) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        byName[name] ?: error("FakeContext: no SharedPreferences registered for name=$name")
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
