# Player Resume-from-Position Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Subagents are EDIT-ONLY; the controller runs ALL gradle/tests/commits/smoke.

**Goal:** When a user reopens a recording in the Player, resume playback from where they left off, using a `sessionId`-canonical identity shared by Library and Player so the saved position survives storage-tier differences and the metadata `prune`.

**Architecture:** A new pure `RecordingIdentity` seam defines the canonical sidecar key (`session:$sessionId`) and per-side position slots, used by BOTH the Library and the Player so they cannot drift. The flat `positionMs` field (interim, landed in #127) is hard-split into a `positionsBySide: Map<String,Long>` carried inside the existing session-level sidecar entry. The `LibraryMetadataStore` gains lazy dual-read + merge-on-write so pre-seam path-keyed favorites/titles migrate without loss. `prune` keeps durable session keys for every finalized manifest. The Player reads/applies the saved position via the existing `ResumePolicy` at attach time and writes the current position back on pause/background/dispose through an application-scoped single-writer (so the final write is not dropped when the ViewModel scope is cancelled).

**Tech Stack:** Kotlin, AndroidX Lifecycle ViewModel, Media3 ExoPlayer 1.4.1, `org.json` (test classpath), JUnit4 JVM unit tests.

## Global Constraints

- **46 static `check*` gates + full `:app:testDebugUnitTest` must be GREEN at EVERY commit.** Never edit a `check*` to pass — fix the source (or amend the ADR + check with owner sign-off).
- **JVM unit tests only.** `testOptions.unitTests.isReturnDefaultValues = true`. Android-framework calls return defaults; `JSONObject` works because `org.json:json:20231013` is on `testImplementation`.
- **Pure-helper extraction pattern** — anything framework-touching gets a pure-Kotlin sibling so it is unit-testable. New pure helpers here: `RecordingIdentity`, `LibraryMetadataEntry.merge`, `PruneKeepSet`.
- **No manifest schema bump this cycle.** Positions live in the sidecar (`LibraryMetadataStore`), not `SessionManifest`. (PR-6b wall-clock playhead is the schema-bump cycle — out of scope.)
- **New feature lands its JVM tests in the same PR.**
- Build WARM — no cache wipe. PowerShell for git/adb. Push/PR/merge ONLY on explicit owner GO.
- Baseline before starting: `1241 tests / 0-0-0` on master. Each task adds tests; the count only grows.

## Identity & migration contract (read once, applies to all tasks)

- **Canonical key** for a manifest-backed recording = `RecordingIdentity.sessionKey(sessionId)` = `"session:$sessionId"`. The Player only ever uses this (it has no file path).
- **Legacy key** = `file.absolutePath ?: docUri` — the pre-seam `stableKey` for file/SAF rows. Used as a grace-period read fallback and migrated-then-removed on first write.
- **Sessionless imports** (no `sessionId`): canonical = legacy key, no migration, never converted.
- **Per-side position slot**: `""` = single-mode, `"PORTRAIT"` / `"LANDSCAPE"` = P+L (DualShot) sides — `VideoSide.name`.
- **Merge rule** (canonical wins, never lose data):
  - `favorite = canonical.favorite || legacy.favorite`
  - `customTitle = canonical.customTitle ?: legacy.customTitle`
  - `lastPlayedAt = maxOfNonNull(canonical.lastPlayedAt, legacy.lastPlayedAt)` — recency must NOT depend on which key won migration (codex catch).
  - `positionsBySide = legacy.positionsBySide + canonical.positionsBySide` (canonical per-side value wins on key collision).

## File Structure

| File | Responsibility | New? |
|---|---|---|
| `ui/library/RecordingIdentity.kt` | Pure seam: `sessionKey`, `legacyKey`, `sideSlot`, `MetaKey`, `forItem` | **new** |
| `ui/library/LibraryMetadataEntry.kt` | Entry model: split `positionMs`→`positionsBySide`; `isEmpty`; companion `merge`; `positionFor` | modify |
| `ui/library/LibraryMetadataCodec.kt` | Encode/decode `positionsBySide` nested object; read legacy flat `positionMs`→`[""]` | modify |
| `ui/library/LibraryMetadataStore.kt` | Dual-read `get(MetaKey)`, merge-on-write `update(MetaKey, …)` | modify |
| `ui/library/PruneKeepSet.kt` | Pure keep-set builder for `prune` | **new** |
| `ui/screens/HistoryViewModel.kt` | Route favorite/title through `MetaKey`; call `prune` with durable keep-set | modify |
| `RovaApp.kt` | Sidecar-write `appScope` (single-thread IO); resume read/write seams; factory wiring | modify |
| `ui/screens/player/PlayerViewModel.kt` | Read+apply saved position at attach; write current position on pause/bg/dispose | modify |

Test files mirror under `app/src/test/java/com/aritr/rova/...`.

---

### Task 1: `RecordingIdentity` pure seam

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/RecordingIdentity.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/RecordingIdentityTest.kt`

**Interfaces:**
- Consumes: `VideoSide` (`com.aritr.rova.service.dualrecord.VideoSide` — `{PORTRAIT, LANDSCAPE}`).
- Produces:
  - `RecordingIdentity.sessionKey(sessionId: String): String`
  - `RecordingIdentity.legacyKey(absolutePath: String?, docUri: String?): String?`
  - `RecordingIdentity.sideSlot(side: VideoSide?): String`
  - `data class MetaKey(val canonical: String, val legacy: String?)`
  - `RecordingIdentity.forItem(sessionId: String?, absolutePath: String?, docUri: String?): MetaKey`

> Note: `forItem` takes the primitive fields, NOT `VideoItem`, so the helper stays Android-free (`VideoItem` carries `android.net.Uri`/`Bitmap`). The caller in `HistoryViewModel` passes `item.sessionId`, `item.file?.absolutePath`, `item.docUri?.toString()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingIdentityTest {
    @Test fun sessionKey_prefixes() {
        assertEquals("session:abc", RecordingIdentity.sessionKey("abc"))
    }

    @Test fun legacyKey_prefersPathThenDocUriThenNull() {
        assertEquals("/p/a.mp4", RecordingIdentity.legacyKey("/p/a.mp4", "doc://x"))
        assertEquals("doc://x", RecordingIdentity.legacyKey(null, "doc://x"))
        assertNull(RecordingIdentity.legacyKey(null, null))
    }

    @Test fun sideSlot_singleIsEmpty_sidesAreNames() {
        assertEquals("", RecordingIdentity.sideSlot(null))
        assertEquals("PORTRAIT", RecordingIdentity.sideSlot(VideoSide.PORTRAIT))
        assertEquals("LANDSCAPE", RecordingIdentity.sideSlot(VideoSide.LANDSCAPE))
    }

    @Test fun forItem_manifestBacked_canonicalIsSession_legacyIsAlias() {
        val k = RecordingIdentity.forItem(sessionId = "s1", absolutePath = "/p/a.mp4", docUri = null)
        assertEquals("session:s1", k.canonical)
        assertEquals("/p/a.mp4", k.legacy)
    }

    @Test fun forItem_sessionless_canonicalIsLegacy_noMigration() {
        val k = RecordingIdentity.forItem(sessionId = null, absolutePath = "/p/a.mp4", docUri = null)
        assertEquals("/p/a.mp4", k.canonical)
        assertNull(k.legacy)
    }

    @Test fun forItem_sessionBackedNoFile_legacyNull() {
        val k = RecordingIdentity.forItem(sessionId = "s2", absolutePath = null, docUri = null)
        assertEquals("session:s2", k.canonical)
        assertNull(k.legacy)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.RecordingIdentityTest"`
Expected: FAIL — `RecordingIdentity` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Canonical recording identity shared by the Library and the Player so a saved playback
 * position (and favorite/title) cannot drift between the two sides.
 *
 * sessionId-canonical (codex-reviewed 2026-06-22): keying by sessionId makes the playable-URI
 * tier (content:// vs _DATA path vs SAF docUri vs vaultfile://) irrelevant to identity.
 * See docs/superpowers/specs/2026-06-22-player-resume-identity-seam-design.md.
 */
object RecordingIdentity {

    /** Session-level metadata key (favorite, customTitle, lastPlayedAt, positionsBySide). */
    fun sessionKey(sessionId: String): String = "session:$sessionId"

    /** Pre-seam stable key a file/SAF row may still be stored under, for dual-read fallback. */
    fun legacyKey(absolutePath: String?, docUri: String?): String? = absolutePath ?: docUri

    /** Per-side playback-position slot inside the session entry. "" = single; side name for P+L. */
    fun sideSlot(side: VideoSide?): String = side?.name ?: ""

    data class MetaKey(val canonical: String, val legacy: String?)

    /**
     * Manifest-backed (sessionId != null): canonical = sessionKey, legacy = path/docUri grace alias.
     * Sessionless import: canonical = legacy key, legacy = null (never auto-converted).
     */
    fun forItem(sessionId: String?, absolutePath: String?, docUri: String?): MetaKey {
        val legacy = legacyKey(absolutePath, docUri)
        return if (sessionId != null) {
            MetaKey(canonical = sessionKey(sessionId), legacy = legacy)
        } else {
            // Sessionless: legacy IS the canonical; nothing to migrate. legacyKey may be null only
            // if a row had neither file, docUri, nor sessionId — not a real row, but keep total.
            MetaKey(canonical = legacy ?: "", legacy = null)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.RecordingIdentityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/RecordingIdentity.kt app/src/test/java/com/aritr/rova/ui/library/RecordingIdentityTest.kt
git commit -m "feat(library): RecordingIdentity seam — sessionId-canonical key + per-side slot"
```

---

### Task 2: `LibraryMetadataEntry` — split `positionMs` → `positionsBySide` + merge

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataEntryTest.kt` (new)

**Interfaces:**
- Produces:
  - `data class LibraryMetadataEntry(favorite, customTitle, lastPlayedAt, positionsBySide: Map<String,Long>)`
  - `LibraryMetadataEntry.isEmpty(): Boolean`
  - `LibraryMetadataEntry.positionFor(slot: String): Long?` (slot fallback to `""`)
  - `LibraryMetadataEntry.withPosition(slot: String, positionMs: Long): LibraryMetadataEntry`
  - `LibraryMetadataEntry.Companion.merge(canonical: LibraryMetadataEntry?, legacy: LibraryMetadataEntry?): LibraryMetadataEntry?`

> **Breaking change:** the flat `positionMs: Long?` field is REMOVED. Task 3 (codec) reads the old JSON `positionMs` key for back-compat; nothing in the model carries it anymore. Search the codebase for `.positionMs` references and update them (Task 3 codec is the main one; if any UI reads it, route through `positionFor("")`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMetadataEntryTest {

    @Test fun isEmpty_trueWhenAllDefault() {
        assertTrue(LibraryMetadataEntry().isEmpty())
    }

    @Test fun isEmpty_falseWhenPositionPresent() {
        assertFalse(LibraryMetadataEntry(positionsBySide = mapOf("" to 5L)).isEmpty())
    }

    @Test fun positionFor_returnsSlot_thenFallsBackToSingle() {
        val e = LibraryMetadataEntry(positionsBySide = mapOf("" to 100L))
        assertEquals(100L, e.positionFor(""))
        // P+L side with no own position falls back to single "" (legacy migration friendliness)
        assertEquals(100L, e.positionFor("PORTRAIT"))
    }

    @Test fun positionFor_sideSpecificWins_noFallbackWhenPresent() {
        val e = LibraryMetadataEntry(positionsBySide = mapOf("" to 100L, "PORTRAIT" to 7L))
        assertEquals(7L, e.positionFor("PORTRAIT"))
    }

    @Test fun positionFor_singleSlotNeverFallsBack() {
        assertNull(LibraryMetadataEntry().positionFor(""))
    }

    @Test fun withPosition_setsSlot_dropsNonPositive() {
        val e = LibraryMetadataEntry().withPosition("", 50L)
        assertEquals(50L, e.positionFor(""))
        val cleared = e.withPosition("", 0L)
        assertNull(cleared.positionFor(""))
    }

    @Test fun merge_favoriteOrTrue_neverUnfavorites() {
        val canonical = LibraryMetadataEntry(favorite = false)
        val legacy = LibraryMetadataEntry(favorite = true)
        assertTrue(LibraryMetadataEntry.merge(canonical, legacy)!!.favorite)
    }

    @Test fun merge_titleCanonicalWins_importsMissing() {
        val canonical = LibraryMetadataEntry(customTitle = "Keep")
        val legacy = LibraryMetadataEntry(customTitle = "Old")
        assertEquals("Keep", LibraryMetadataEntry.merge(canonical, legacy)!!.customTitle)
        assertEquals("Old", LibraryMetadataEntry.merge(LibraryMetadataEntry(), legacy)!!.customTitle)
    }

    @Test fun merge_lastPlayedAtTakesMax_notKeyWinner() {
        val canonical = LibraryMetadataEntry(lastPlayedAt = 10L)
        val legacy = LibraryMetadataEntry(lastPlayedAt = 99L)
        assertEquals(99L, LibraryMetadataEntry.merge(canonical, legacy)!!.lastPlayedAt)
    }

    @Test fun merge_positionsCanonicalPerSideWins_importsOthers() {
        val canonical = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 5L))
        val legacy = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 1L, "LANDSCAPE" to 9L))
        val merged = LibraryMetadataEntry.merge(canonical, legacy)!!
        assertEquals(5L, merged.positionFor("PORTRAIT"))
        assertEquals(9L, merged.positionFor("LANDSCAPE"))
    }

    @Test fun merge_bothNull_returnsNull_oneNull_returnsOther() {
        assertNull(LibraryMetadataEntry.merge(null, null))
        assertEquals("X", LibraryMetadataEntry.merge(null, LibraryMetadataEntry(customTitle = "X"))!!.customTitle)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataEntryTest"`
Expected: FAIL — `positionsBySide` / `merge` / `positionFor` unresolved.

- [ ] **Step 3: Write the implementation**

Replace the entire file body:

```kotlin
package com.aritr.rova.ui.library

/**
 * Session-level sidecar metadata for one recording. favorite/customTitle/lastPlayedAt are
 * session-level; playback position is per-side (DualShot P+L has two independent streams), carried
 * in [positionsBySide] keyed by RecordingIdentity.sideSlot ("" = single).
 */
data class LibraryMetadataEntry(
    val favorite: Boolean = false,
    val customTitle: String? = null,
    val lastPlayedAt: Long? = null,
    val positionsBySide: Map<String, Long> = emptyMap(),
) {
    fun isEmpty(): Boolean =
        !favorite && customTitle == null && lastPlayedAt == null && positionsBySide.isEmpty()

    /** Saved position for a side slot; a P+L side with no own value falls back to single "". */
    fun positionFor(slot: String): Long? =
        positionsBySide[slot] ?: if (slot.isNotEmpty()) positionsBySide[""] else null

    /** Returns a copy with [slot] set to [positionMs], or the slot dropped when non-positive. */
    fun withPosition(slot: String, positionMs: Long): LibraryMetadataEntry {
        val next = positionsBySide.toMutableMap()
        if (positionMs > 0L) next[slot] = positionMs else next.remove(slot)
        return copy(positionsBySide = next)
    }

    companion object {
        /**
         * Merge a canonical (session-key) entry with a legacy (path-key) one. Canonical wins per
         * set field, but data is never lost: favorite OR-merges (never un-favorites), lastPlayedAt
         * takes the max (recency independent of which key won migration — codex), positions union
         * with canonical per-side winning.
         */
        fun merge(canonical: LibraryMetadataEntry?, legacy: LibraryMetadataEntry?): LibraryMetadataEntry? {
            if (canonical == null) return legacy
            if (legacy == null) return canonical
            val positions = LinkedHashMap<String, Long>(legacy.positionsBySide)
            positions.putAll(canonical.positionsBySide)
            return LibraryMetadataEntry(
                favorite = canonical.favorite || legacy.favorite,
                customTitle = canonical.customTitle ?: legacy.customTitle,
                lastPlayedAt = maxOfNonNull(canonical.lastPlayedAt, legacy.lastPlayedAt),
                positionsBySide = positions,
            )
        }

        private fun maxOfNonNull(a: Long?, b: Long?): Long? =
            when {
                a == null -> b
                b == null -> a
                else -> maxOf(a, b)
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataEntryTest"`
Expected: PASS. (Codec will not compile yet — that's Task 3. Do NOT run the full suite at this step; run it after Task 3.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataEntryTest.kt
git commit -m "feat(library): entry model — positionsBySide split + lossless merge"
```

> The repo will not fully compile between Task 2 and Task 3 (codec still references the removed `positionMs`). These two tasks are reviewed as a pair; the controller runs the full gate suite at the END of Task 3.

---

### Task 3: `LibraryMetadataCodec` — `positionsBySide` JSON + legacy flat read

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataCodecTest.kt` (extend)

**Interfaces:**
- Consumes: `LibraryMetadataEntry` (Task 2), `org.json`.
- Produces: unchanged signatures `toJson(Map): String`, `fromJson(String): Map`, `prune(Map, Set): Map`.

JSON shape change: an entry now writes a nested `"positionsBySide": {"": 50000, "PORTRAIT": 1200}` object (positions `> 0` only). On read: prefer `positionsBySide`; if absent, read the old flat `"positionMs"` into `positionsBySide[""]`. Stop writing `positionMs`.

- [ ] **Step 1: Write the failing tests** (append to existing `LibraryMetadataCodecTest`)

```kotlin
    @Test fun roundTrip_positionsBySide() {
        val map = mapOf("session:s1" to LibraryMetadataEntry(positionsBySide = mapOf("" to 50_000L, "PORTRAIT" to 1_200L)))
        val decoded = LibraryMetadataCodec.fromJson(LibraryMetadataCodec.toJson(map))
        assertEquals(50_000L, decoded["session:s1"]!!.positionFor(""))
        assertEquals(1_200L, decoded["session:s1"]!!.positionFor("PORTRAIT"))
    }

    @Test fun read_legacyFlatPositionMs_mapsToSingleSlot() {
        val json = """{"session:s1":{"positionMs":42000}}"""
        val decoded = LibraryMetadataCodec.fromJson(json)
        assertEquals(42_000L, decoded["session:s1"]!!.positionFor(""))
    }

    @Test fun write_dropsNonPositivePositions_andOmitsEmptyMap() {
        val map = mapOf("session:s1" to LibraryMetadataEntry(favorite = true, positionsBySide = mapOf("" to 0L)))
        val json = LibraryMetadataCodec.toJson(map)
        assertFalse("must not write positionsBySide for empty/zero", json.contains("positionsBySide"))
        assertFalse("must not write legacy positionMs", json.contains("positionMs"))
    }

    @Test fun read_prefersPositionsBySideOverLegacyFlat() {
        val json = """{"session:s1":{"positionMs":1,"positionsBySide":{"":9}}}"""
        assertEquals(9L, LibraryMetadataCodec.fromJson(json)["session:s1"]!!.positionFor(""))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataCodecTest"`
Expected: FAIL (compile error — old codec references removed `positionMs`; new tests unresolved).

- [ ] **Step 3: Rewrite the codec**

```kotlin
package com.aritr.rova.ui.library

import org.json.JSONObject

object LibraryMetadataCodec {
    private const val FAVORITE = "favorite"
    private const val CUSTOM_TITLE = "customTitle"
    private const val LAST_PLAYED_AT = "lastPlayedAt"
    private const val POSITIONS_BY_SIDE = "positionsBySide"
    private const val LEGACY_POSITION_MS = "positionMs" // read-only back-compat (#127 interim)

    fun toJson(map: Map<String, LibraryMetadataEntry>): String {
        val root = JSONObject()
        for ((key, e) in map) {
            if (e.isEmpty()) continue
            val obj = JSONObject()
            if (e.favorite) obj.put(FAVORITE, true)
            if (e.customTitle != null) obj.put(CUSTOM_TITLE, e.customTitle)
            if (e.lastPlayedAt != null) obj.put(LAST_PLAYED_AT, e.lastPlayedAt)
            val positive = e.positionsBySide.filterValues { it > 0L }
            if (positive.isNotEmpty()) {
                val pos = JSONObject()
                for ((slot, v) in positive) pos.put(slot, v)
                obj.put(POSITIONS_BY_SIDE, pos)
            }
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
                        positionsBySide = readPositions(obj),
                    )
                    if (!entry.isEmpty()) put(key, entry)
                }
            }
        } catch (e: Exception) {
            emptyMap() // Tolerant: corrupt sidecar never crashes the Library.
        }
    }

    private fun readPositions(obj: JSONObject): Map<String, Long> {
        val nested = obj.optJSONObject(POSITIONS_BY_SIDE)
        if (nested != null) {
            val out = LinkedHashMap<String, Long>()
            for (slot in nested.keys()) {
                val v = nested.optLong(slot)
                if (v > 0L) out[slot] = v
            }
            return out
        }
        // Back-compat: old flat positionMs → single slot.
        if (obj.has(LEGACY_POSITION_MS)) {
            val v = obj.optLong(LEGACY_POSITION_MS)
            if (v > 0L) return mapOf("" to v)
        }
        return emptyMap()
    }

    fun prune(map: Map<String, LibraryMetadataEntry>, keep: Set<String>): Map<String, LibraryMetadataEntry> =
        map.filter { (k, v) -> k in keep && !v.isEmpty() }
}
```

- [ ] **Step 4: Run codec + entry tests, then the FULL suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.*"` then `./gradlew :app:testDebugUnitTest`
Expected: all PASS. (Controller also runs `./gradlew :app:lintDebug` to fire the 46 gates.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataCodec.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataCodecTest.kt
git commit -m "feat(library): codec writes positionsBySide; reads legacy flat positionMs"
```

---

### Task 4: `LibraryMetadataStore` — dual-read + merge-on-write

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataStore.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataStoreTest.kt` (extend)

**Interfaces:**
- Consumes: `RecordingIdentity.MetaKey` (Task 1), `LibraryMetadataEntry.merge` (Task 2).
- Produces (ADD; keep existing `get(String)`, `snapshot()`, `prune(Set)`, `update(String, …)` for callers not yet migrated):
  - `get(key: RecordingIdentity.MetaKey): LibraryMetadataEntry?` — merged dual-read.
  - `update(key: RecordingIdentity.MetaKey, transform: (LibraryMetadataEntry) -> LibraryMetadataEntry)` — merge base, write canonical, remove legacy key.

- [ ] **Step 1: Write failing tests** (append)

```kotlin
    @Test fun dualRead_returnsLegacyWhenCanonicalAbsent() {
        val store = LibraryMetadataStore(tmpDir)
        store.update("/p/a.mp4") { it.copy(favorite = true) } // legacy-only entry
        val key = RecordingIdentity.MetaKey(canonical = "session:s1", legacy = "/p/a.mp4")
        assertTrue(store.get(key)!!.favorite)
    }

    @Test fun mergeOnWrite_migratesLegacyToCanonical_andRemovesLegacy() {
        val store = LibraryMetadataStore(tmpDir)
        store.update("/p/a.mp4") { it.copy(favorite = true, customTitle = "Old") }
        val key = RecordingIdentity.MetaKey("session:s1", "/p/a.mp4")
        store.update(key) { it.withPosition("", 5_000L) } // first canonical write
        // Reload from disk to prove persistence.
        val reloaded = LibraryMetadataStore(tmpDir)
        assertNull("legacy key removed", reloaded.get("/p/a.mp4"))
        val merged = reloaded.get(key)!!
        assertTrue(merged.favorite)           // preserved through migration
        assertEquals("Old", merged.customTitle)
        assertEquals(5_000L, merged.positionFor(""))
    }

    @Test fun update_canonicalNoLegacy_writesCanonicalOnly() {
        val store = LibraryMetadataStore(tmpDir)
        val key = RecordingIdentity.MetaKey("session:s9", legacy = null)
        store.update(key) { it.withPosition("", 1_000L) }
        assertEquals(1_000L, LibraryMetadataStore(tmpDir).get(key)!!.positionFor(""))
    }

    @Test fun update_pLSides_doNotOverwriteEachOther() {
        val store = LibraryMetadataStore(tmpDir)
        val key = RecordingIdentity.MetaKey("session:s1", legacy = null)
        store.update(key) { it.withPosition("PORTRAIT", 7L) }
        store.update(key) { it.withPosition("LANDSCAPE", 9L) }
        val e = LibraryMetadataStore(tmpDir).get(key)!!
        assertEquals(7L, e.positionFor("PORTRAIT"))
        assertEquals(9L, e.positionFor("LANDSCAPE"))
    }
```

> `tmpDir` is whatever the existing `LibraryMetadataStoreTest` uses for `filesDir` (a JUnit `@Rule TemporaryFolder` or a `File` field). Reuse the existing fixture; do not invent a new one.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataStoreTest"`
Expected: FAIL — `get(MetaKey)` / `update(MetaKey, …)` unresolved.

- [ ] **Step 3: Add the dual-read API** (insert into `LibraryMetadataStore`, alongside existing methods)

```kotlin
    /** Merged dual-read: canonical entry merged over the legacy alias (if any). */
    fun get(key: RecordingIdentity.MetaKey): LibraryMetadataEntry? {
        val map = load()
        val canonical = map[key.canonical]
        val legacy = key.legacy?.takeIf { it != key.canonical }?.let { map[it] }
        return LibraryMetadataEntry.merge(canonical, legacy)
    }

    /**
     * Merge-on-write: the transform sees the merged (canonical ⊕ legacy) base, the result is written
     * under the canonical key, and the legacy alias — if distinct — is removed (migration complete).
     */
    fun update(key: RecordingIdentity.MetaKey, transform: (LibraryMetadataEntry) -> LibraryMetadataEntry) {
        synchronized(lock) {
            val map = load()
            val canonical = map[key.canonical]
            val legacyKey = key.legacy?.takeIf { it != key.canonical }
            val legacy = legacyKey?.let { map[it] }
            val base = LibraryMetadataEntry.merge(canonical, legacy) ?: LibraryMetadataEntry()
            val next = transform(base)
            if (next.isEmpty()) map.remove(key.canonical) else map[key.canonical] = next
            if (legacyKey != null) map.remove(legacyKey)
            writeAtomic(map)
        }
    }
```

- [ ] **Step 4: Run store tests then full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataStoreTest"` then `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataStore.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataStoreTest.kt
git commit -m "feat(library): store dual-read + merge-on-write (lazy legacy migration)"
```

---

### Task 5: `PruneKeepSet` pure helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/PruneKeepSet.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/PruneKeepSetTest.kt`

**Interfaces:**
- Consumes: `RecordingIdentity.sessionKey`.
- Produces: `PruneKeepSet.build(finalizedSessionIds: Collection<String>, visibleLegacyKeys: Collection<String?>, existingKeys: Collection<String>): Set<String>`

Keep-set composition (codex-hardened):
1. `sessionKey(id)` for EVERY finalized manifest on disk (durable — independent of whether the row resolved this refresh, incl. vaulted/non-visible).
2. legacy key for each currently-visible row (sessionless imports + grace alias).
3. **Blanket grace:** every existing non-`session:` key currently in the store — pre-seam path/docUri favorites that haven't migrated yet cannot be reliably reconstructed from a TIER1 manifest (the fragile `_DATA` the spec avoids), so they are never pruned. (Bounded-growth follow-up: dead sessionless-import keys accumulate; cleaned only when their file row re-appears-then-migrates. Filed in BACKLOG.)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PruneKeepSetTest {

    @Test fun keepsSessionKeyForEveryFinalizedManifest() {
        val keep = PruneKeepSet.build(
            finalizedSessionIds = listOf("s1", "s2"),
            visibleLegacyKeys = emptyList(),
            existingKeys = emptyList(),
        )
        assertTrue("session:s1" in keep)
        assertTrue("session:s2" in keep)
    }

    @Test fun keepsVisibleLegacyKeys_ignoresNulls() {
        val keep = PruneKeepSet.build(
            finalizedSessionIds = emptyList(),
            visibleLegacyKeys = listOf("/p/a.mp4", null),
            existingKeys = emptyList(),
        )
        assertTrue("/p/a.mp4" in keep)
        assertFalse(keep.contains(null as String?))
    }

    @Test fun blanketGrace_keepsExistingNonSessionKeys_dropsOrphanSessionKeys() {
        val keep = PruneKeepSet.build(
            finalizedSessionIds = listOf("s1"),
            visibleLegacyKeys = emptyList(),
            existingKeys = listOf("/p/legacyFav.mp4", "session:deleted"),
        )
        assertTrue("/p/legacyFav.mp4" in keep)         // grace: never prune a legacy alias
        assertFalse("session:deleted" in keep)          // orphan session key not kept
        assertTrue("session:s1" in keep)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.PruneKeepSetTest"`
Expected: FAIL — `PruneKeepSet` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

/**
 * Builds the keep-set for [LibraryMetadataStore.prune] from durable sources, not the
 * momentarily-visible row set — fixing the orphan-prune bug where a TIER1 row whose MediaStore
 * _DATA query transiently returns null would lose its favorite/title/position.
 */
object PruneKeepSet {
    private const val SESSION_PREFIX = "session:"

    fun build(
        finalizedSessionIds: Collection<String>,
        visibleLegacyKeys: Collection<String?>,
        existingKeys: Collection<String>,
    ): Set<String> {
        val keep = LinkedHashSet<String>()
        for (id in finalizedSessionIds) keep += RecordingIdentity.sessionKey(id)
        for (k in visibleLegacyKeys) if (k != null) keep += k
        // Blanket grace: never prune a not-yet-migrated legacy (non-session) alias.
        for (k in existingKeys) if (!k.startsWith(SESSION_PREFIX)) keep += k
        return keep
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.PruneKeepSetTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/PruneKeepSet.kt app/src/test/java/com/aritr/rova/ui/library/PruneKeepSetTest.kt
git commit -m "feat(library): PruneKeepSet — durable session keys + blanket legacy grace"
```

---

### Task 6: `HistoryViewModel` — route metadata through `MetaKey` + call `prune`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`

**Interfaces:**
- Consumes: `RecordingIdentity.forItem`, `LibraryMetadataEntry.merge`, `PruneKeepSet.build`, `SessionStore.listSessionIds`/`loadManifest`.
- Produces: no new public surface; behavior changes only.

This task is Android-VM glue; its pure logic is already covered by Tasks 1–5 tests. No new JVM test is required here (the keep-set logic is tested via `PruneKeepSetTest`; identity via `RecordingIdentityTest`). The controller verifies via the full build + device smoke.

- [ ] **Step 1: Read the current file** and locate three spots:
  1. `toLibraryRow(item, meta, …)` — `meta` currently comes from `snapshot[item.stableKey]`.
  2. `toggleFavorite(stableKey)` / `renameSession(stableKey, …)` — write via `store.update(stableKey) { … }`.
  3. The point where the visible list and `visibleFinalizedManifests` are built (end of the items refresh) — where `prune` will be called.

- [ ] **Step 2: Change the metadata READ to a merged dual-read.**

In the `libraryUiState` mapping, replace the per-row `snapshot[item.stableKey]` lookup with a merged read. Keep using `snapshot()` (one map read), but resolve through identity:

```kotlin
val snapshot = libraryStore?.snapshot() ?: emptyMap()
// ...
val mapped = rows.map { item ->
    val key = RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString())
    val canonical = snapshot[key.canonical]
    val legacy = key.legacy?.takeIf { it != key.canonical }?.let { snapshot[it] }
    val meta = LibraryMetadataEntry.merge(canonical, legacy)
    toLibraryRow(item, meta, locale, tz)
}
```

(`toLibraryRow` keeps its `meta: LibraryMetadataEntry?` parameter — it already reads `meta?.favorite`, `meta?.customTitle`. No change needed inside it for this cycle; position is not surfaced in the Library row UI here.)

- [ ] **Step 3: Change the WRITES to identity-keyed.**

`toggleFavorite` / `renameSession` currently receive a `stableKey: String` from the row. Convert that back to a `MetaKey` by finding the originating item from the current items list:

```kotlin
private fun metaKeyForStableKey(stableKey: String): RecordingIdentity.MetaKey? {
    val item = items.value.firstOrNull { it.stableKey == stableKey } ?: return null
    return RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString())
}

fun toggleFavorite(stableKey: String) {
    val store = libraryStore ?: return
    val key = metaKeyForStableKey(stableKey) ?: return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            store.update(key) { it.copy(favorite = !it.favorite) }
            _sidecarRevision.update { it + 1 }
        } catch (t: Throwable) {
            RovaLog.e("HistoryViewModel.toggleFavorite: sidecar write failed for $stableKey", t)
            _sidecarWriteError.update { it + 1 }
        }
    }
}
```

Apply the same `metaKeyForStableKey` + `store.update(key) { … }` swap to `renameSession`. (`items` is the existing `StateFlow<List<VideoItem>>` — confirm its name while reading; if it is named differently, use that name.)

- [ ] **Step 4: Call `prune` after a refresh.**

At the end of the items-refresh path (where `visibleFinalizedManifests` / the durable manifest list is available), build the keep-set and prune. The durable finalized session IDs must include ALL finalized manifests on disk (not just Library-visible — vaulted included), so enumerate from `SessionStore` directly:

```kotlin
private fun pruneSidecar(visibleItems: List<VideoItem>) {
    val store = libraryStore ?: return
    val sessionStore = app.sessionStore // or however this VM holds it; reuse existing accessor
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val finalizedIds = sessionStore.listSessionIds()
                .mapNotNull { sid -> runCatching { sessionStore.loadManifest(sid) }.getOrNull() }
                .filter { /* finalized */ it.exportState == ExportState.FINALIZED }
                .map { it.sessionId }
            val visibleLegacy = visibleItems.map {
                RecordingIdentity.legacyKey(it.file?.absolutePath, it.docUri?.toString())
            }
            val existing = store.snapshot().keys
            store.prune(PruneKeepSet.build(finalizedIds, visibleLegacy, existing))
        } catch (t: Throwable) {
            RovaLog.e("HistoryViewModel.pruneSidecar failed", t)
        }
    }
}
```

> While reading the file, match the exact accessors: how the VM reaches `SessionStore` (likely `app.sessionStore` or an injected field), the finalized predicate (reuse `HistoryArtifactMapper.finalizedManifests` if that is the established way rather than a raw `exportState` check), and the visible-items source. Use the established APIs — do not introduce a parallel manifest-enumeration path that a `check*` gate would flag. Call `pruneSidecar(...)` once per successful refresh, off the main thread.

- [ ] **Step 5: Controller builds + gates + commits**

Run: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug` (46 gates) and `./gradlew :app:assembleDebug`.
Expected: all GREEN.

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "feat(library): History routes sidecar through RecordingIdentity + prunes durably"
```

---

### Task 7: `RovaApp` — sidecar app-scope + resume seams + factory wiring

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt` (factory signature only; VM body is Task 8)

**Interfaces:**
- Produces:
  - `RovaApp.sidecarWriteScope: CoroutineScope` — `SupervisorJob() + Dispatchers.IO.limitedParallelism(1)` (single-thread-confined so writes execute in submission order; survives ViewModel `onCleared`).
  - Seam lambdas passed into the player factory:
    - `readResume: suspend (sessionId: String, side: VideoSide?) -> Long?`
    - `writeResume: (sessionId: String, side: VideoSide?, positionMs: Long) -> Unit` (fire-and-forget on `sidecarWriteScope`)

- [ ] **Step 1: Read `RovaApp.kt`** and confirm how `libraryStore` is exposed (the Explore map shows `HistoryViewModel` already uses `libraryStore` from the app; reuse that exact property — likely `RovaApp.libraryStore: LibraryMetadataStore?`). Confirm `PlayerViewModel.factory(app, sessionId, side)` is the call site (it is, per the map).

- [ ] **Step 2: Add the app-scope + seam builders to `RovaApp`.**

```kotlin
// In RovaApp:
val sidecarWriteScope: CoroutineScope by lazy {
    CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
}

/** Reads the saved resume position for a recording's side, or null. */
suspend fun readResumePosition(sessionId: String, side: VideoSide?): Long? {
    val store = libraryStore ?: return null
    val key = RecordingIdentity.MetaKey(RecordingIdentity.sessionKey(sessionId), legacy = null)
    return store.get(key)?.positionFor(RecordingIdentity.sideSlot(side))
}

/** Persists the current playback position; serialized + survives ViewModel teardown. */
fun writeResumePosition(sessionId: String, side: VideoSide?, positionMs: Long) {
    val store = libraryStore ?: return
    val key = RecordingIdentity.MetaKey(RecordingIdentity.sessionKey(sessionId), legacy = null)
    val slot = RecordingIdentity.sideSlot(side)
    sidecarWriteScope.launch {
        runCatching { store.update(key) { it.withPosition(slot, positionMs) } }
            .onFailure { RovaLog.e("writeResumePosition failed for $sessionId/$slot", it) }
    }
}
```

> The Player uses canonical-only `MetaKey` (legacy = null) because it only has a `sessionId`, never a file path — the legacy migration is a Library concern. `limitedParallelism(1)` makes pause-then-dispose writes deterministic (no reorder regressing the position); the store's `synchronized(lock)` makes each write atomic vs the Library's writes.

- [ ] **Step 3: Widen the player factory to accept the seams.**

In `PlayerViewModel.factory`, thread the two seams through (default the `loadManifest` wiring stays as-is):

```kotlin
fun factory(app: RovaApp, sessionId: String, side: VideoSide? = null): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val sessionStoreAvailable = app.videosRoot != null
            val loader: suspend (String) -> SessionManifest? =
                if (sessionStoreAvailable) { id -> app.sessionStore.loadManifest(id) } else { _ -> null }
            return PlayerViewModel(
                application = app,
                sessionId = sessionId,
                side = side,
                loadManifest = loader,
                readResume = { sid, s -> app.readResumePosition(sid, s) },
                writeResume = { sid, s, pos -> app.writeResumePosition(sid, s, pos) },
            ) as T
        }
    }
```

(The `PlayerViewModel` constructor gains the two seam params in Task 8; this step only updates the factory's call. If executing strictly task-by-task, Task 7 will not compile until Task 8's constructor exists — the controller runs the build at the END of Task 8. Tasks 7 and 8 are reviewed as a pair.)

- [ ] **Step 4: Commit (no standalone build — paired with Task 8)**

```bash
git add app/src/main/java/com/aritr/rova/RovaApp.kt app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt
git commit -m "feat(player): app-scope sidecar writer + resume read/write seams"
```

---

### Task 8: `PlayerViewModel` — apply saved position on open, write on pause/dispose

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/PlayerViewModelResumeTest.kt` (new)

**Interfaces:**
- Consumes: `ResumePolicy.resolveOpenPosition`, the `readResume`/`writeResume` seams (Task 7), `PlayerUiState.Ready.totalDurationFromSegmentsMs`.
- Produces:
  - Constructor params `readResume: suspend (String, VideoSide?) -> Long?` and `writeResume: (String, VideoSide?, Long) -> Unit`.
  - Pure-testable behavior: on attach, the start position = `ResumePolicy.resolveOpenPosition(savedRaw, totalDurationFromSegmentsMs)`; on pause/background/dispose, `writeResume(sessionId, side, currentPosition)` is invoked.

> **Why duration-from-segments, not ExoPlayer.duration:** ExoPlayer's duration is unknown until `STATE_READY`. The Player already has `Ready.totalDurationFromSegmentsMs` at attach time, so compute the resume target there and pass it as the ExoPlayer initial seek — no first-frame flash, and `ResumePolicy`'s near-end logic has a duration to work with immediately.

- [ ] **Step 1: Write the failing test** (pure seams; ExoPlayer is not constructed — the test exercises only the resume-resolution + write-trigger logic, which we isolate behind small internal functions).

To make the VM testable without ExoPlayer/Android, extract the two decisions into internal pure functions on the VM and test those plus the seam calls:

```kotlin
package com.aritr.rova.ui.screens.player

import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerViewModelResumeTest {

    @Test fun resolveStart_appliesResumePolicy() {
        // 50s saved into a 100s recording → resume at 50s.
        assertEquals(50_000L, PlayerResumeMath.startPositionMs(saved = 50_000L, durationMs = 100_000L))
        // null saved → start at 0.
        assertEquals(0L, PlayerResumeMath.startPositionMs(saved = null, durationMs = 100_000L))
        // near-end → restart at 0.
        assertEquals(0L, PlayerResumeMath.startPositionMs(saved = 99_900L, durationMs = 100_000L))
    }

    @Test fun sideSlotIsThreaded_singleVsPL() {
        assertEquals("", com.aritr.rova.ui.library.RecordingIdentity.sideSlot(null))
        assertEquals("LANDSCAPE", com.aritr.rova.ui.library.RecordingIdentity.sideSlot(VideoSide.LANDSCAPE))
    }
}
```

Create the pure helper it references:

`app/src/main/java/com/aritr/rova/ui/screens/player/PlayerResumeMath.kt`

```kotlin
package com.aritr.rova.ui.screens.player

/** Pure resume-position resolution seam (framework-free, JVM-testable). */
object PlayerResumeMath {
    fun startPositionMs(saved: Long?, durationMs: Long): Long =
        ResumePolicy.resolveOpenPosition(saved, durationMs)
}
```

> This is intentionally a thin pure seam around the already-tested `ResumePolicy`, giving the Player a unit-testable entry point without standing up ExoPlayer (which cannot run under `isReturnDefaultValues`). The write-trigger wiring is verified on-device (Task 9 smoke), per the JVM-only test policy.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.PlayerViewModelResumeTest"`
Expected: FAIL — `PlayerResumeMath` unresolved.

- [ ] **Step 3: Add constructor seams + wire read/apply/write in `PlayerViewModel`.**

1. Constructor:

```kotlin
class PlayerViewModel(
    application: Application,
    private val sessionId: String,
    private val side: VideoSide?,
    private val loadManifest: suspend (String) -> SessionManifest?,
    private val readResume: suspend (String, VideoSide?) -> Long? = { _, _ -> null },
    private val writeResume: (String, VideoSide?, Long) -> Unit = { _, _, _ -> },
) : AndroidViewModel(application)
```

2. In `init`, AFTER resolving to `PlayerUiState.Ready`, read the saved position and compute the start, then attach with it:

```kotlin
(_uiState.value as? PlayerUiState.Ready)?.let { ready ->
    segmentDurationsMs = ready.segmentDurationsMs
    val saved = runCatching { readResume(sessionId, side) }.getOrNull()
    val startMs = PlayerResumeMath.startPositionMs(saved, ready.totalDurationFromSegmentsMs)
    attachExoPlayer(ready.mediaUri, startMs)
}
```

> The existing code attaches inside `PlayerStateEmitter.emit(resolved) { uri -> attachExoPlayer(uri) }`. Refactor so the attach happens once, after the `Ready` branch is known, passing `startMs`. If `PlayerStateEmitter.emit` must still own the attach for `Unavailable`/error handling, pass a closure that calls `attachExoPlayer(uri, startMs)` — read the current structure and adapt; do not duplicate the emit logic.

3. `attachExoPlayer` gains a start position and seeks before prepare:

```kotlin
private fun attachExoPlayer(uri: String, startMs: Long = 0L) {
    val app = getApplication<Application>()
    val player = ExoPlayer.Builder(app).build().apply {
        setMediaItem(MediaItem.fromUri(resolvePlaybackUri(app, uri)))
        addListener(playerListener)
        if (startMs > 0L) seekTo(startMs) // honored as the initial position on prepare
        playWhenReady = true
        prepare()
    }
    exoPlayer = player
    pushProgress()
}
```

4. Write the position on every pause path AND on dispose:

```kotlin
private fun persistPosition() {
    val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: return
    writeResume(sessionId, side, pos)
}

fun togglePlayPause() {
    val p = exoPlayer ?: return
    if (p.isPlaying) { p.pause(); persistPosition() } else p.play()
    pushProgress()
}

fun pauseForBackground() {
    exoPlayer?.takeIf { it.isPlaying }?.pause()
    persistPosition()
    pushProgress(isPlaying = false)
}

override fun onCleared() {
    persistPosition() // writeResume runs on the app scope — NOT viewModelScope — so it is not dropped here
    stopPolling()
    exoPlayer?.let { it.removeListener(playerListener); it.release() }
    exoPlayer = null
    super.onCleared()
}
```

> `writeResume` dispatches onto `RovaApp.sidecarWriteScope` (Task 7), which outlives `onCleared`, so the final write is durable. Capturing `currentPosition` synchronously before the scope launch means the value is read while the player is still alive.

- [ ] **Step 4: Controller runs the build + full suite + gates**

Run: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug` and `./gradlew :app:assembleDebug`.
Expected: all GREEN (Tasks 7 + 8 now compile together).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt app/src/main/java/com/aritr/rova/ui/screens/player/PlayerResumeMath.kt app/src/test/java/com/aritr/rova/ui/screens/player/PlayerViewModelResumeTest.kt
git commit -m "feat(player): resume from saved position; persist on pause/background/dispose"
```

---

### Task 9: Integration build + device smoke

**Files:** none (verification only).

- [ ] **Step 1: Full clean-ish verification (WARM build — no cache wipe)**

```powershell
./gradlew :app:lintDebug          # 46 gates
./gradlew :app:testDebugUnitTest  # full JVM suite, expect 0-0-0, count > 1241
./gradlew :app:assembleDebug
```

- [ ] **Step 2: Install + smoke on RZCYA1VBQ2H (Android 14)**

```powershell
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```

Smoke script (device was `pm clear`-ed — no recordings exist):
1. Grant permissions; record **two** short sessions (≥ ~30s each so a mid-point resume is observable). One single-mode; if feasible one DualShot (P+L) to exercise per-side slots.
2. Open recording A in the Player, let it play ~15s, press back (dispose) or background the app.
3. Reopen recording A → **playback resumes near ~15s**, not 0. ✅
4. Scrub to near the end (within ~2s of end), back out, reopen → **restarts from 0** (near-end policy). ✅
5. For P+L: resume PORTRAIT to ~10s, back out, open LANDSCAPE → LANDSCAPE starts at 0 (independent slot), reopen PORTRAIT → ~10s. ✅
6. Favorite a recording, force-stop + relaunch → favorite persists (sidecar intact, prune did not drop it). ✅
7. Confirm no crash, no ANR, Library list unaffected.

- [ ] **Step 3: Report smoke results to owner. Do NOT push/PR/merge without explicit GO.**

---

## Self-Review

**Spec coverage:**
- §1 `RecordingIdentity` (sessionKey/legacyKey/sideSlot) → Task 1. ✅
- §2 entry-model split `positionsBySide` + forward-compat codec → Tasks 2, 3. ✅
- §3 lazy dual-read + merge-on-write, legacy removed on first write, sessionless never converted → Task 4 (+ `forItem` in Task 1). ✅
- §4 prune keep-set from durable manifests not visible rows → Task 5 (pure) + Task 6 (wiring). ✅ (Hardened past spec: blanket legacy grace, per codex, because TIER1 legacy alias can't be reconstructed from the manifest.)
- §5 Player wiring (read positionsBySide[slot], ResumePolicy, seek; write on pause/dispose) → Tasks 7, 8. ✅
- JVM tests 1–8 from the spec → covered: #1 identity (Task1), #2 prune (Task5), #3/#4 dual-read+merge (Task4/Task2), #5 P+L isolation (Task4), #6 manifest-delete prune (Task5 orphan-drop), #7 sessionless never converted (Task1/Task4), #8 codec legacy flat (Task3). ✅

**Placeholder scan:** No TBD/"add error handling"/"similar to Task N" — all code shown. Steps 1 & 4 of Task 6 reference "match exact accessors while reading" — this is deliberate (the VM's private field names must be read live), and the established APIs to use are named (`SessionStore.listSessionIds`/`loadManifest`, `HistoryArtifactMapper.finalizedManifests`, `items` StateFlow).

**Type consistency:** `MetaKey(canonical, legacy)`, `LibraryMetadataEntry.merge(canonical, legacy)`, `positionFor(slot)`, `withPosition(slot, positionMs)`, `sideSlot(side)`, `PlayerResumeMath.startPositionMs(saved, durationMs)`, `readResume(sessionId, side)`, `writeResume(sessionId, side, positionMs)` — names used identically across Tasks 1–8. ✅

## Codex peer-review fold (2026-06-23)

Folded before planning: (1) `lastPlayedAt` merge = max-non-null, not key-winner. (2) `positionFor` falls back to `""` so migrated legacy flat resumes for P+L. (3) prune keep-set blanket-keeps legacy aliases (TIER1 alias not reconstructable from manifest → would lose vaulted legacy favorites). (4)+(5) final write on app-scope single-thread dispatcher, not `viewModelScope` (cancelled in `onCleared`) — fixes drop + reorder. (6) `ResumePolicy` `saved>=dur→dur` inconsistency left AS-IS (landed + tested + owner-approved in #127); noted as a possible follow-up, not changed without sign-off. (A) favorite resurrection acceptable (legacy removed first write). (D) hard-split `positionMs` now; keep tolerant read indefinitely.

## Out of scope (do NOT build this cycle)
- PR-6b wall-clock playhead (needs `SessionManifest` schema bump for per-segment wall-start).
- PR-7 speed / double-tap / auto-hide.
- Surfacing a "Resume / Restart" chip in the Library row UI (positions are read by the Player only this cycle).
- Changing `ResumePolicy` end-overrun behavior (owner sign-off required).
