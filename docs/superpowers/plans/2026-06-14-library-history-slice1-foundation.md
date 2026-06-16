# Enhanced Library & History — Slice 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the data + infrastructure foundation for the Library/History redesign — a sidecar metadata store
(favorite / rename / lastPlayedAt), a disk thumbnail cache, the pure query/format/badge/title helpers, ADR-0030,
and the 42nd static gate — with **no visible UI change** beyond wiring the disk cache into the existing load path.

**Architecture:** Library UI metadata lives in a **sidecar** `LibraryMetadataStore` (JSON keyed by `stableKey`),
**never** in `SessionManifest` — this designs out the terminal-state write race (codex-reviewed). All decision
logic is in pure-Kotlin helpers (JVM-testable under `isReturnDefaultValues = true`); the Android-touching pieces
(Bitmap↔bytes, MediaMetadataRetriever) stay thin seams. A new `checkLibraryNoManifestWrite` gate forbids Library
code from calling any `SessionStore` manifest-mutating API.

**Tech Stack:** Kotlin, `org.json` (real, on `testImplementation`), JUnit4 (`org.junit.Test` + `org.junit.Assert`),
Gradle Kotlin DSL `check*` tasks wired into `preBuild`.

**Spec:** `docs/superpowers/specs/2026-06-14-enhanced-library-history-design.md` (§3, §6, §7, §10, §11 slice 1).

---

## File structure

New package `com.aritr.rova.ui.library` groups all redesign code (clean boundary the gate can scope to). Tests
mirror under `app/src/test/java/com/aritr/rova/ui/library`.

| File | Responsibility |
|---|---|
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt` | Pure data class: `favorite`, `customTitle`, `lastPlayedAt` |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt` | Pure JSON (de)serialize + prune of the `stableKey → entry` map |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataStore.kt` | Atomic file-backed store (mirrors `SessionStore` write discipline) |
| Create `app/src/main/java/com/aritr/rova/ui/library/SmartTitle.kt` | Pure: manifest primitives → derived title |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryBadge.kt` | Enum `RECOVERED` / `INTERRUPTED` |
| Create `app/src/main/java/com/aritr/rova/ui/library/StatusBadgePolicy.kt` | Pure: `(Terminated?, ExportState)` → `LibraryBadge?` (exceptional-only) |
| Create `app/src/main/java/com/aritr/rova/ui/library/StorageFormat.kt` | Pure: byte-size + per-day-total formatting |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt` | Pure row model + `LibrarySort` / `LibraryFilter` |
| Create `app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt` | Pure: hero extraction + filter + search + sort (hero de-dup) |
| Create `app/src/main/java/com/aritr/rova/ui/library/ThumbnailCacheKey.kt` | Pure: invalidator-enriched cache key |
| Create `app/src/main/java/com/aritr/rova/ui/library/ThumbnailDiskCache.kt` | File-bytes cache + LRU eviction (JVM-testable) |
| Modify `app/build.gradle.kts` | Add `checkLibraryNoManifestWrite` + wire into `preBuild` |
| Create `docs/adr/0030-library-history-information-architecture.md` | ADR-0030 |

Test files: one `*Test.kt` per pure helper + the store + the cache + the codec (paths under each task).

**Build/verify command (this repo, Windows):** `gradlew.bat :app:testDebugUnitTest` for tests;
`gradlew.bat :app:assembleDebug` to run the gate suite (gate runs at `preBuild`). `lintDebug` is RED on a
pre-existing unrelated issue — gate-build with `assembleDebug`. Builds are WARM — no `--stop`, no cache wipe.

> Run a single test: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SmartTitleTest"`

---

## Task 1: ADR-0030

**Files:**
- Create: `docs/adr/0030-library-history-information-architecture.md`

- [ ] **Step 1: Write the ADR**

```markdown
# ADR-0030: Library/History information architecture

Status: Accepted (2026-06-14)

## Context
The Library/History surface (browse all recordings) is being redesigned to the Liquid Glass language. The
redesign adds favorite, rename, sort/filter/search, multi-select batch actions, and a disk thumbnail cache.
Two design decisions need a recorded invariant.

## Decision

1. **Export is not a Library action.** Every FINALIZED session already lives in user-visible storage at record
   time (Tier1 MediaStore, Tier2/3 `Movies/Rova`, or the SAF folder). `Share` hands a content-URI to another app
   (it does not save a copy). `ExportPipeline.export()` is callable only from `RovaRecordingService.performMerge`
   and the cold-launch recovery runner — not from UI. A Library "Export"/"Save" button would solve no real user
   problem. If a session's public artifact later goes missing, the UX is "file missing → remove from Library",
   not re-export. Vault sessions leave via the existing move-out.

2. **Library UI metadata lives in a sidecar, never in `SessionManifest`.** `favorite`, `customTitle`, and a
   reserved `lastPlayedAt` are stored in `LibraryMetadataStore` (a JSON file keyed by row `stableKey`),
   completely separate from the manifest. Rationale: with whole-file atomic-rename manifest writes, a UI write
   that started from a non-terminal snapshot can lose a race to the service writing `COMPLETED` and resurrect
   stale terminal state. Keeping UI metadata out of the manifest removes the shared file, so the race is
   impossible. Invariant: **Library/History code never calls a `SessionManifest`-mutating `SessionStore` API**
   (`markTerminated`, `appendSegment`, `submitPersistFinalizedSegment`, `setExport*`, `setVault*`,
   `setStopRequested`, `setPendingMoveOut*`). Enforced by `checkLibraryNoManifestWrite`.

3. **Status badges are exceptional-only.** Cards show no "Complete" badge. `RECOVERED` is surfaced for
   `MULTI_SEGMENT_KEPT`; `INTERRUPTED` for `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` or `exportState == FAILED`.
   All other states (including `COMPLETED`, clean `USER_STOPPED`) show no badge.

4. **Vault stays a separate auth-gated destination.** Vaulted sessions never appear in the main grid; the
   redesign only re-skins the existing vault entry to glass.

5. **Captions/badges over thumbnails carry a structural scrim** guaranteeing ≥4.5:1 (≥3:1 large) at the worst
   pixel — not a token gate (the background is an arbitrary video frame).

## Consequences
- No manifest schema change; no migration. Legacy file-scan rows (no manifest) gain favorite/rename for free
  (the sidecar keys on `stableKey`, which exists for every row).
- A new static gate (`checkLibraryNoManifestWrite`, the 42nd) enforces decision 2.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0030-library-history-information-architecture.md
git commit -m "docs(adr): ADR-0030 Library/History IA — sidecar metadata, export-cut, badge policy"
```

---

## Task 2: `LibraryMetadataEntry`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt`

- [ ] **Step 1: Write the data class** (no test needed — a plain value type; it is exercised by the codec test)

```kotlin
package com.aritr.rova.ui.library

/**
 * ADR-0030 — one Library sidecar record, keyed externally by row `stableKey`.
 * Lives in [LibraryMetadataStore], never in SessionManifest.
 *
 * @property favorite user-pinned star.
 * @property customTitle user rename; when null the UI shows the [SmartTitle] derived label.
 * @property lastPlayedAt reserved (owner #3): written best-effort on player open; not surfaced in v1 UI.
 */
data class LibraryMetadataEntry(
    val favorite: Boolean = false,
    val customTitle: String? = null,
    val lastPlayedAt: Long? = null,
) {
    /** True when this entry holds nothing worth persisting (lets the store prune empty rows). */
    fun isEmpty(): Boolean = !favorite && customTitle == null && lastPlayedAt == null
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt
git commit -m "feat(library): LibraryMetadataEntry sidecar value type (ADR-0030)"
```

---

## Task 3: `LibraryMetadataCodec`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataCodecTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMetadataCodecTest {

    @Test
    fun `round-trips entries`() {
        val map = mapOf(
            "/a/b.mp4" to LibraryMetadataEntry(favorite = true, customTitle = "Beach", lastPlayedAt = 42L),
            "content://x/1" to LibraryMetadataEntry(favorite = false, customTitle = null, lastPlayedAt = null),
        )
        val json = LibraryMetadataCodec.toJson(map)
        val back = LibraryMetadataCodec.fromJson(json)
        assertEquals(map, back)
    }

    @Test
    fun `fromJson tolerates empty and garbage`() {
        assertTrue(LibraryMetadataCodec.fromJson("").isEmpty())
        assertTrue(LibraryMetadataCodec.fromJson("not json").isEmpty())
        assertTrue(LibraryMetadataCodec.fromJson("{}").isEmpty())
    }

    @Test
    fun `fromJson tolerates missing fields with defaults`() {
        val back = LibraryMetadataCodec.fromJson("""{"/a.mp4":{"favorite":true}}""")
        assertEquals(LibraryMetadataEntry(favorite = true), back["/a.mp4"])
    }

    @Test
    fun `prune drops empty entries and keys not in keepSet`() {
        val map = mapOf(
            "keep" to LibraryMetadataEntry(favorite = true),
            "empty" to LibraryMetadataEntry(),
            "gone" to LibraryMetadataEntry(customTitle = "x"),
        )
        val pruned = LibraryMetadataCodec.prune(map, keep = setOf("keep", "empty"))
        assertTrue(pruned.containsKey("keep"))
        assertFalse("empty entry dropped", pruned.containsKey("empty"))
        assertFalse("key not in keep set dropped", pruned.containsKey("gone"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataCodecTest"`
Expected: FAIL — `LibraryMetadataCodec` unresolved.

- [ ] **Step 3: Write the codec**

```kotlin
package com.aritr.rova.ui.library

import org.json.JSONObject

/**
 * ADR-0030 — pure (de)serialize of the sidecar `stableKey → entry` map.
 * Uses org.json (real impl on testImplementation, so this is JVM-testable).
 * Tolerant: any parse failure yields an empty map (the sidecar is best-effort
 * cosmetic metadata — a corrupt file must never crash the Library).
 */
object LibraryMetadataCodec {

    private const val FAVORITE = "favorite"
    private const val CUSTOM_TITLE = "customTitle"
    private const val LAST_PLAYED_AT = "lastPlayedAt"

    fun toJson(map: Map<String, LibraryMetadataEntry>): String {
        val root = JSONObject()
        for ((key, e) in map) {
            if (e.isEmpty()) continue
            val obj = JSONObject()
            if (e.favorite) obj.put(FAVORITE, true)
            if (e.customTitle != null) obj.put(CUSTOM_TITLE, e.customTitle)
            if (e.lastPlayedAt != null) obj.put(LAST_PLAYED_AT, e.lastPlayedAt)
            root.put(key, obj)
        }
        return root.toString()
    }

    fun fromJson(text: String): Map<String, LibraryMetadataEntry> {
        if (text.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(text)
            buildMap {
                for (key in root.keys()) {
                    val obj = root.optJSONObject(key) ?: continue
                    val entry = LibraryMetadataEntry(
                        favorite = obj.optBoolean(FAVORITE, false),
                        customTitle = if (obj.has(CUSTOM_TITLE) && !obj.isNull(CUSTOM_TITLE)) obj.getString(CUSTOM_TITLE) else null,
                        lastPlayedAt = if (obj.has(LAST_PLAYED_AT) && !obj.isNull(LAST_PLAYED_AT)) obj.getLong(LAST_PLAYED_AT) else null,
                    )
                    if (!entry.isEmpty()) put(key, entry)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Drop empty entries and any key absent from [keep] (deleted rows). */
    fun prune(map: Map<String, LibraryMetadataEntry>, keep: Set<String>): Map<String, LibraryMetadataEntry> =
        map.filter { (k, v) -> k in keep && !v.isEmpty() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataCodecTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataCodecTest.kt
git commit -m "feat(library): LibraryMetadataCodec pure JSON + prune (ADR-0030)"
```

---

## Task 4: `LibraryMetadataStore`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataStore.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataStoreTest.kt`

- [ ] **Step 1: Write the failing test** (uses a real temp dir — `java.io.File` works under JVM tests)

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMetadataStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = LibraryMetadataStore(tmp.newFolder("files"))

    @Test
    fun `set then read back favorite and title`() {
        val s = store()
        s.update("/a.mp4") { it.copy(favorite = true, customTitle = "Run") }
        assertEquals(LibraryMetadataEntry(favorite = true, customTitle = "Run"), s.get("/a.mp4"))
    }

    @Test
    fun `persists across instances`() {
        val dir = tmp.newFolder("files2")
        LibraryMetadataStore(dir).update("/a.mp4") { it.copy(favorite = true) }
        assertTrue(LibraryMetadataStore(dir).get("/a.mp4")!!.favorite)
    }

    @Test
    fun `clearing an entry to empty removes it`() {
        val s = store()
        s.update("/a.mp4") { it.copy(favorite = true) }
        s.update("/a.mp4") { it.copy(favorite = false) }
        assertNull(s.get("/a.mp4"))
    }

    @Test
    fun `prune removes keys for deleted rows`() {
        val s = store()
        s.update("/a.mp4") { it.copy(favorite = true) }
        s.update("/b.mp4") { it.copy(favorite = true) }
        s.prune(setOf("/a.mp4"))
        assertTrue(s.get("/a.mp4")!!.favorite)
        assertNull(s.get("/b.mp4"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataStoreTest"`
Expected: FAIL — `LibraryMetadataStore` unresolved.

- [ ] **Step 3: Write the store** (atomic temp-then-rename, mirroring `SessionStore.writeManifestAtomic`)

```kotlin
package com.aritr.rova.ui.library

import java.io.File

/**
 * ADR-0030 — file-backed sidecar for Library UI metadata (favorite / rename /
 * lastPlayedAt), keyed by row `stableKey`. NEVER touches SessionManifest, so the
 * terminal-state write race cannot occur. Atomic temp-then-rename writes mirror
 * SessionStore's durability contract.
 *
 * Thread-safety: in-memory map guarded by a lock; each write rewrites the whole
 * file (the map is tiny — one small record per recording). Callers invoke off the
 * main thread.
 *
 * @param filesDir a stable app-internal directory (production: `context.filesDir`).
 */
class LibraryMetadataStore(private val filesDir: File) {

    private val target = File(filesDir, FILE_NAME)
    private val tmp = File(filesDir, "$FILE_NAME.tmp")
    private val lock = Any()

    @Volatile private var cache: MutableMap<String, LibraryMetadataEntry>? = null

    private fun load(): MutableMap<String, LibraryMetadataEntry> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            if (!filesDir.exists()) filesDir.mkdirs()
            val text = if (target.exists()) target.readText() else ""
            val loaded = LibraryMetadataCodec.fromJson(text).toMutableMap()
            cache = loaded
            return loaded
        }
    }

    fun get(stableKey: String): LibraryMetadataEntry? = load()[stableKey]

    /** Snapshot copy for merging into the row model at load time. */
    fun snapshot(): Map<String, LibraryMetadataEntry> = load().toMap()

    /** Apply [transform] to the current (or empty) entry; empties are dropped. */
    fun update(stableKey: String, transform: (LibraryMetadataEntry) -> LibraryMetadataEntry) {
        synchronized(lock) {
            val map = load()
            val next = transform(map[stableKey] ?: LibraryMetadataEntry())
            if (next.isEmpty()) map.remove(stableKey) else map[stableKey] = next
            writeAtomic(map)
        }
    }

    /** Drop entries whose key is not in [keep] (deleted/moved-out rows). */
    fun prune(keep: Set<String>) {
        synchronized(lock) {
            val map = load()
            val pruned = LibraryMetadataCodec.prune(map, keep)
            if (pruned.size != map.size) {
                map.clear(); map.putAll(pruned); writeAtomic(map)
            }
        }
    }

    private fun writeAtomic(map: Map<String, LibraryMetadataEntry>) {
        if (!filesDir.exists()) filesDir.mkdirs()
        tmp.writeText(LibraryMetadataCodec.toJson(map))
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) { target.writeText(tmp.readText()); tmp.delete() }
        }
    }

    companion object { const val FILE_NAME = "library_metadata.json" }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataStoreTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataStore.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataStoreTest.kt
git commit -m "feat(library): LibraryMetadataStore atomic sidecar (ADR-0030)"
```

---

## Task 5: `checkLibraryNoManifestWrite` gate (42nd)

**Files:**
- Modify: `app/build.gradle.kts` (add the task near the other `check*` tasks, ~line 1817; wire into the
  `preBuild` `afterEvaluate` block, ~line 2411)

- [ ] **Step 1: Add the gate task** (place after `checkPresetNoOrientation`, before `checkNoLegacyModeStrings`)

```kotlin
// ADR-0030 gate (42nd) — Library/History UI must never mutate a SessionManifest.
// Favorite/rename/lastPlayedAt go only through LibraryMetadataStore (sidecar),
// so the terminal-state write race is impossible. Forbid the manifest-mutating
// SessionStore setters in any ui/library or ui/screens History source. Read-only
// APIs (loadManifest/listSessionIds) and discardSession (file delete, not a
// manifest write) are allowed. Comment/KDoc lines are skipped.
val checkLibraryNoManifestWrite = tasks.register("checkLibraryNoManifestWrite") {
    group = "verification"
    description = "Library/History code must not call SessionManifest-mutating SessionStore APIs (ADR-0030)."
    val forbidden = listOf(
        "markTerminated(", "appendSegment(", "submitPersistFinalizedSegment(",
        "setExportPending(", "setExportPrivateTarget(", "setExportCopying(",
        "setExportSafPrivateTemp(", "setExportSafTarget(", "setExportFinalized(",
        "setExportFailed(", "setMediaScanCompleted(", "incrementSafTransientRetry(",
        "setExportPendingForSide(", "setExportPrivateTargetForSide(",
        "setExportSafPrivateTempForSide(", "setExportSafTargetForSide(",
        "setExportFinalizedForSide(", "setMediaScanCompletedForSide(",
        "setVaultFinalized(", "setVaultFinalizedForSide(", "setVaultState(",
        "setVaultMovedOut(", "setVaultStateVaultedAndClearPublic(",
        "setPendingMoveOutTier1(", "setPendingMoveOutPreQ(", "setStopRequested(",
        "writeManifestAtomic(",
    )
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            val inScope = rel.startsWith("ui/library/") ||
                (rel.startsWith("ui/screens/") && (rel.contains("History") || rel.contains("Library")))
            if (!inScope) return@forEach
            f.readLines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                if (forbidden.any { line.contains(it) }) offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0030: Library/History must not mutate SessionManifest — use LibraryMetadataStore:\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`** — in the `afterEvaluate { tasks.matching { it.name == "preBuild" } ... }`
  block, add a line alongside the other `dependsOn(...)` calls (e.g. after `dependsOn(checkPresetNoOrientation)`):

```kotlin
        dependsOn(checkLibraryNoManifestWrite)
```

- [ ] **Step 3: Run the gate to verify it passes on current source** (no Library code calls these yet)

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — `checkLibraryNoManifestWrite` runs at preBuild and finds no offenders.

- [ ] **Step 4: Verify it actually fires** (temporary negative check — do NOT commit this)

Temporarily add `sessionStore.markTerminated(` inside a comment-free line in any `ui/screens/History*.kt`, run
`gradlew.bat :app:assembleDebug`, confirm it FAILS with the ADR-0030 message, then revert the edit.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(gate): checkLibraryNoManifestWrite (42nd) — Library never mutates manifest (ADR-0030)"
```

---

## Task 6: `SmartTitle`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/SmartTitle.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/SmartTitleTest.kt`

- [ ] **Step 1: Write the failing test** (pin Locale/TZ like `HistoryRowFormattersTest`)

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SmartTitleTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz, locale); c.clear(); c.set(y, mo, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
    }

    @Test
    fun `derives day time clips and duration`() {
        val t = millis(2026, Calendar.JUNE, 14, 14, 32) // Sunday
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m",
            SmartTitle.derive(t, segmentCount = 8, totalDurationMs = 12 * 60_000L, locale, tz)
        )
    }

    @Test
    fun `single clip is not pluralized and seconds shown under a minute`() {
        val t = millis(2026, Calendar.JUNE, 14, 9, 5)
        assertEquals(
            "Sun · 9:05 AM · 1 clip · 42s",
            SmartTitle.derive(t, segmentCount = 1, totalDurationMs = 42_000L, locale, tz)
        )
    }

    @Test
    fun `minutes and seconds combine above a minute`() {
        val t = millis(2026, Calendar.JUNE, 14, 9, 5)
        assertEquals(
            "Sun · 9:05 AM · 2 clips · 1m 30s",
            SmartTitle.derive(t, segmentCount = 2, totalDurationMs = 90_000L, locale, tz)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SmartTitleTest"`
Expected: FAIL — `SmartTitle` unresolved.

- [ ] **Step 3: Write the helper**

```kotlin
package com.aritr.rova.ui.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ADR-0030 — derives the default Library title for a session that has no
 * user [LibraryMetadataEntry.customTitle]. Pure: takes manifest-derived
 * primitives, returns a string. Format: `EEE · h:mm a · N clip(s) · <dur>`.
 */
object SmartTitle {

    fun derive(
        startedAtMillis: Long,
        segmentCount: Int,
        totalDurationMs: Long,
        locale: Locale,
        tz: TimeZone,
    ): String {
        val day = SimpleDateFormat("EEE", locale).apply { timeZone = tz }.format(Date(startedAtMillis))
        val time = SimpleDateFormat("h:mm a", locale).apply { timeZone = tz }.format(Date(startedAtMillis))
        val clips = if (segmentCount == 1) "1 clip" else "$segmentCount clips"
        return "$day · $time · $clips · ${formatDuration(totalDurationMs)}"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return when {
            m == 0L -> "${s}s"
            s == 0L -> "${m}m"
            else -> "${m}m ${s}s"
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SmartTitleTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/SmartTitle.kt app/src/test/java/com/aritr/rova/ui/library/SmartTitleTest.kt
git commit -m "feat(library): SmartTitle derived label (ADR-0030)"
```

---

## Task 7: `LibraryBadge` + `StatusBadgePolicy`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryBadge.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/library/StatusBadgePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/StatusBadgePolicyTest.kt`

> `Terminated` values: `USER_STOPPED, COMPLETED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP, MULTI_SEGMENT_KEPT`.
> `ExportState` values: `NOT_STARTED, MUXING, COPYING, FINALIZED, FAILED`. Both in `com.aritr.rova.data`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusBadgePolicyTest {

    @Test fun `completed clean session has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.COMPLETED, ExportState.FINALIZED))
    }

    @Test fun `clean user stop has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(Terminated.USER_STOPPED, ExportState.FINALIZED))
    }

    @Test fun `multi-segment kept is Recovered`() {
        assertEquals(LibraryBadge.RECOVERED, StatusBadgePolicy.badgeFor(Terminated.MULTI_SEGMENT_KEPT, ExportState.FINALIZED))
    }

    @Test fun `system kill is Interrupted`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_BY_SYSTEM, ExportState.FINALIZED))
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.KILLED_FORCE_STOP, ExportState.FINALIZED))
    }

    @Test fun `failed export is Interrupted even if terminal is clean`() {
        assertEquals(LibraryBadge.INTERRUPTED, StatusBadgePolicy.badgeFor(Terminated.COMPLETED, ExportState.FAILED))
    }

    @Test fun `null terminated with finalized export has no badge`() {
        assertNull(StatusBadgePolicy.badgeFor(null, ExportState.FINALIZED))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.StatusBadgePolicyTest"`
Expected: FAIL — `LibraryBadge` / `StatusBadgePolicy` unresolved.

- [ ] **Step 3: Write the enum + policy**

`LibraryBadge.kt`:
```kotlin
package com.aritr.rova.ui.library

/** ADR-0030 — exceptional-only card badges. No "Complete" badge exists by design. */
enum class LibraryBadge { RECOVERED, INTERRUPTED }
```

`StatusBadgePolicy.kt`:
```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated

/**
 * ADR-0030 — maps a session's terminal + export state to an exceptional badge,
 * or null for the clean common case (Complete / clean user-stop show nothing).
 * Pure; enum-only inputs.
 */
object StatusBadgePolicy {
    fun badgeFor(terminated: Terminated?, exportState: ExportState): LibraryBadge? = when {
        exportState == ExportState.FAILED -> LibraryBadge.INTERRUPTED
        terminated == Terminated.KILLED_BY_SYSTEM -> LibraryBadge.INTERRUPTED
        terminated == Terminated.KILLED_FORCE_STOP -> LibraryBadge.INTERRUPTED
        terminated == Terminated.MULTI_SEGMENT_KEPT -> LibraryBadge.RECOVERED
        else -> null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.StatusBadgePolicyTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryBadge.kt app/src/main/java/com/aritr/rova/ui/library/StatusBadgePolicy.kt app/src/test/java/com/aritr/rova/ui/library/StatusBadgePolicyTest.kt
git commit -m "feat(library): LibraryBadge + StatusBadgePolicy exceptional-only (ADR-0030)"
```

---

## Task 8: `StorageFormat`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/StorageFormat.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/StorageFormatTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.StorageFormatTest"`
Expected: FAIL — `StorageFormat` unresolved.

- [ ] **Step 3: Write the helper**

```kotlin
package com.aritr.rova.ui.library

import java.util.Locale

/** ADR-0030 — pure human-readable byte sizes for cards + per-day header totals. */
object StorageFormat {

    fun size(bytes: Long, locale: Locale): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0; unitIndex++
        }
        return String.format(locale, "%.1f %s", value, units[unitIndex])
    }

    fun dayTotal(sizes: List<Long>, locale: Locale): String = size(sizes.sum(), locale)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.StorageFormatTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/StorageFormat.kt app/src/test/java/com/aritr/rova/ui/library/StorageFormatTest.kt
git commit -m "feat(library): StorageFormat byte sizing (ADR-0030)"
```

---

## Task 9: `LibraryRow` model + `LibrarySort` + `LibraryFilter`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt`

> Pure view-model row. The History ViewModel maps `VideoItem` + manifest + sidecar → `LibraryRow` (wired in
> Slice 2). `CaptureTopology` is `com.aritr.rova.data.CaptureTopology` (`Single`, `DualShot`, `FrontBack`).

- [ ] **Step 1: Write the model** (exercised by the `LibraryQuery` test in Task 10 — no standalone test)

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology

/**
 * ADR-0030 — pure row model the Library grid/list/hero render and [LibraryQuery]
 * operates over. Built by the ViewModel from VideoItem + SessionManifest + the
 * sidecar; carries no Android types so it is fully JVM-testable.
 *
 * @property title resolved display title (customTitle ?: SmartTitle).
 * @property dateLabel pre-formatted date string used for search matching.
 */
data class LibraryRow(
    val stableKey: String,
    val title: String,
    val dateLabel: String,
    val dateMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val topology: CaptureTopology,
    val badge: LibraryBadge?,
    val favorite: Boolean,
)

/** Sort options for the Library (decision C). */
enum class LibrarySort { NEWEST, OLDEST, LONGEST, LARGEST }

/**
 * Filter facets (decision C). [topology] null = any. [search] blank = no search.
 * Vault is a separate destination, not a filter here (ADR-0030).
 */
data class LibraryFilter(
    val favoritesOnly: Boolean = false,
    val topology: CaptureTopology? = null,
    val search: String = "",
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt
git commit -m "feat(library): LibraryRow model + LibrarySort + LibraryFilter (ADR-0030)"
```

---

## Task 10: `LibraryQuery` (hero + filter + search + sort, with hero de-dup)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryQueryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    private fun row(
        key: String, date: Long, dur: Long = 0, size: Long = 0,
        topology: CaptureTopology = CaptureTopology.Single,
        favorite: Boolean = false, title: String = key, dateLabel: String = "",
    ) = LibraryRow(key, title, dateLabel, date, dur, size, topology, null, favorite)

    private val a = row("a", date = 300, dur = 10, size = 100, title = "Beach run")
    private val b = row("b", date = 200, dur = 50, size = 30, favorite = true, title = "Park")
    private val c = row("c", date = 100, dur = 5, size = 999, topology = CaptureTopology.DualShot)
    private val all = listOf(b, c, a) // intentionally unsorted

    @Test fun `hero is the newest by date`() {
        assertEquals("a", LibraryQuery.hero(all)!!.stableKey)
    }

    @Test fun `hero is null for empty`() {
        assertEquals(null, LibraryQuery.hero(emptyList()))
    }

    @Test fun `collection excludes the hero and sorts newest-first`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(), heroKey = "a")
        assertEquals(listOf("b", "c"), out.map { it.stableKey })
    }

    @Test fun `oldest sort`() {
        val out = LibraryQuery.collection(all, LibrarySort.OLDEST, LibraryFilter(), heroKey = null)
        assertEquals(listOf("c", "b", "a"), out.map { it.stableKey })
    }

    @Test fun `longest then largest sorts`() {
        assertEquals(
            listOf("b", "a", "c"),
            LibraryQuery.collection(all, LibrarySort.LONGEST, LibraryFilter(), heroKey = null).map { it.stableKey }
        )
        assertEquals(
            listOf("c", "a", "b"),
            LibraryQuery.collection(all, LibrarySort.LARGEST, LibraryFilter(), heroKey = null).map { it.stableKey }
        )
    }

    @Test fun `favorites filter`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(favoritesOnly = true), heroKey = null)
        assertEquals(listOf("b"), out.map { it.stableKey })
    }

    @Test fun `topology filter`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(topology = CaptureTopology.DualShot), heroKey = null)
        assertEquals(listOf("c"), out.map { it.stableKey })
    }

    @Test fun `search matches title case-insensitively`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(search = "beach"), heroKey = null)
        assertEquals(listOf("a"), out.map { it.stableKey })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryQueryTest"`
Expected: FAIL — `LibraryQuery` unresolved.

- [ ] **Step 3: Write the query engine**

```kotlin
package com.aritr.rova.ui.library

/**
 * ADR-0030 — pure Library query layer. [hero] picks the newest row; [collection]
 * filters (favorites / topology / search), sorts, and excludes the hero's key so
 * the same recording never appears twice (owner #4). Search matches title or
 * dateLabel substring, case-insensitive.
 */
object LibraryQuery {

    fun hero(rows: List<LibraryRow>): LibraryRow? = rows.maxByOrNull { it.dateMillis }

    fun collection(
        rows: List<LibraryRow>,
        sort: LibrarySort,
        filter: LibraryFilter,
        heroKey: String?,
    ): List<LibraryRow> {
        val q = filter.search.trim().lowercase()
        val filtered = rows.asSequence()
            .filter { it.stableKey != heroKey }
            .filter { !filter.favoritesOnly || it.favorite }
            .filter { filter.topology == null || it.topology == filter.topology }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.dateLabel.lowercase().contains(q) }
            .toList()
        return when (sort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.dateMillis }
            LibrarySort.OLDEST -> filtered.sortedBy { it.dateMillis }
            LibrarySort.LONGEST -> filtered.sortedByDescending { it.durationMs }
            LibrarySort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryQueryTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt app/src/test/java/com/aritr/rova/ui/library/LibraryQueryTest.kt
git commit -m "feat(library): LibraryQuery hero+filter+search+sort with hero de-dup (ADR-0030)"
```

---

## Task 11: `ThumbnailCacheKey`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/ThumbnailCacheKey.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/ThumbnailCacheKeyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCacheKeyTest {

    @Test fun `same inputs give same key`() {
        assertEquals(
            ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 10),
            ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 10),
        )
    }

    @Test fun `changing any invalidator changes the key`() {
        val base = ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 10)
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/a.mp4", 101, 5, 10))
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/a.mp4", 100, 6, 10))
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 11))
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/b.mp4", 100, 5, 10))
    }

    @Test fun `key is a filesystem-safe hex string`() {
        val k = ThumbnailCacheKey.keyFor("content://x/1", 100, 5, 10)
        assertTrue(k.matches(Regex("[0-9a-f]+")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ThumbnailCacheKeyTest"`
Expected: FAIL — `ThumbnailCacheKey` unresolved.

- [ ] **Step 3: Write the helper**

```kotlin
package com.aritr.rova.ui.library

import java.security.MessageDigest

/**
 * ADR-0030 / spec §7 — disk thumbnail cache key. Hashes the row identity plus
 * invalidators (size, last-modified, duration) so a changed/replaced file at the
 * same path or content-URI never serves a stale thumbnail. Returns lowercase hex
 * (filesystem-safe cache filename stem). Pure.
 */
object ThumbnailCacheKey {

    fun keyFor(stableKey: String, sizeBytes: Long, lastModified: Long, durationMs: Long): String {
        val raw = "$stableKey|$sizeBytes|$lastModified|$durationMs"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ThumbnailCacheKeyTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/ThumbnailCacheKey.kt app/src/test/java/com/aritr/rova/ui/library/ThumbnailCacheKeyTest.kt
git commit -m "feat(library): ThumbnailCacheKey invalidator-enriched key (ADR-0030)"
```

---

## Task 12: `ThumbnailDiskCache`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/ThumbnailDiskCache.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/ThumbnailDiskCacheTest.kt`

> Stores raw bytes keyed by `ThumbnailCacheKey` output. Bitmap↔WebP (compress/decode) is the Android seam done
> by the caller (ViewModel/loader) in Slice 2; this cache is pure file-bytes + LRU and is fully JVM-testable.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailDiskCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `put then get returns bytes`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 10_000)
        cache.put("k1", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("k1"))
    }

    @Test fun `get returns null for a miss`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 10_000)
        assertNull(cache.get("nope"))
    }

    @Test fun `persists across instances`() {
        val dir = tmp.newFolder("thumbs")
        ThumbnailDiskCache(dir, maxBytes = 10_000).put("k", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), ThumbnailDiskCache(dir, maxBytes = 10_000).get("k"))
    }

    @Test fun `evicts least-recently-used when over budget`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 300)
        cache.put("a", ByteArray(200))
        cache.put("b", ByteArray(200)) // total 400 > 300 → evict LRU ("a")
        assertNull(cache.get("a"))
        assertTrue(cache.get("b") != null)
    }

    @Test fun `removeAllExcept prunes deleted rows`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 10_000)
        cache.put("keep", byteArrayOf(1))
        cache.put("drop", byteArrayOf(2))
        cache.removeAllExcept(setOf("keep"))
        assertTrue(cache.get("keep") != null)
        assertNull(cache.get("drop"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ThumbnailDiskCacheTest"`
Expected: FAIL — `ThumbnailDiskCache` unresolved.

- [ ] **Step 3: Write the cache**

```kotlin
package com.aritr.rova.ui.library

import java.io.File

/**
 * ADR-0030 / spec §7 — disk thumbnail cache. Stores raw (already-encoded) bytes
 * under [dir], keyed by [ThumbnailCacheKey] output. Size-bounded LRU by file
 * last-modified. Pure file I/O → JVM-testable; the Bitmap↔WebP encode/decode is
 * the caller's Android seam.
 */
class ThumbnailDiskCache(private val dir: File, private val maxBytes: Long) {

    init { if (!dir.exists()) dir.mkdirs() }

    private fun fileFor(key: String) = File(dir, "$key.thumb")

    fun get(key: String): ByteArray? {
        val f = fileFor(key)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis()) // touch → LRU recency
        return f.readBytes()
    }

    fun put(key: String, bytes: ByteArray) {
        fileFor(key).writeBytes(bytes)
        evictIfNeeded()
    }

    /** Delete cache files whose key is not in [keep] (deleted/moved-out rows). */
    fun removeAllExcept(keep: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val key = f.name.removeSuffix(".thumb")
            if (key !in keep) f.delete()
        }
    }

    private fun evictIfNeeded() {
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() } // oldest first
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.ThumbnailDiskCacheTest"`
Expected: PASS (5 tests).

> Note: the LRU test relies on `lastModified()` ordering. If the host filesystem has coarse mtime granularity and
> the test flakes, insert `Thread.sleep(5)` between the two `put` calls in the eviction test — but try without
> first; `writeBytes` + the `put` ordering is normally sufficient.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/ThumbnailDiskCache.kt app/src/test/java/com/aritr/rova/ui/library/ThumbnailDiskCacheTest.kt
git commit -m "feat(library): ThumbnailDiskCache size-bounded LRU (ADR-0030, spec §7)"
```

---

## Task 13: Full-suite verification + gate run

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit-test suite**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all prior tests still pass; the new `com.aritr.rova.ui.library.*Test` classes pass.

- [ ] **Step 2: Run the gate suite via assembleDebug**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all 42 `check*` gates pass (including the new `checkLibraryNoManifestWrite`).

- [ ] **Step 3: Confirm no visible UI change**

Foundation adds only data/infra. `HistoryScreen` rendering is unchanged in this slice (the disk cache and sidecar
are consumed by Slice 2). No device smoke is strictly required for Foundation, but installing the debug APK and
opening Library to confirm it still loads is a cheap sanity check.

- [ ] **Step 4: Final commit (if any stragglers)**

```bash
git status   # expect clean
```

---

## Self-review (completed during authoring)

- **Spec coverage:** Foundation items in spec §11 slice 1 — sidecar store ✅(T2–4), `checkLibraryNoManifestWrite`
  ✅(T5), `SmartTitle` ✅(T6), disk thumbnail cache + invalidator key ✅(T11–12), `LibraryQuery` ✅(T10),
  `StatusBadgePolicy` ✅(T7), `StorageFormat` ✅(T8), ADR-0030 ✅(T1), reserved `lastPlayedAt` ✅(T2). UI surfacing
  of favorite/rename and the VideoItem→LibraryRow mapping are **Slice 2/3** (intentionally out of Foundation).
- **Placeholder scan:** none — every code step shows complete code.
- **Type consistency:** `LibraryMetadataEntry`, `LibraryRow`, `LibrarySort`, `LibraryFilter`, `LibraryBadge`,
  method names (`update`/`get`/`snapshot`/`prune`, `hero`/`collection`, `keyFor`, `get`/`put`/`removeAllExcept`)
  are used identically across tasks and tests.
- **Real-code anchors:** atomic write mirrors `SessionStore.writeManifestAtomic`; gate DSL mirrors
  `checkPresetNoOrientation`/`checkNoLegacyModeStrings`; test style mirrors `HistoryRowFormattersTest`;
  `stableKey`/`Terminated`/`ExportState`/`CaptureTopology` are the real identifiers.

---

## Next slices (separate plans, written when reached)

2. Layout — Hero + Grid + List toggle + glass re-skin (+ its a11y).
3. Selection + batch actions (+ `SelectionReducer`, its a11y).
4. Discovery — sort/filter/search UI + `ScrubberIndex` scrubber (+ its a11y).
5. Accessibility close-out — rows 23/32 + row 21 `SegmentedTimelineSemantics`.
