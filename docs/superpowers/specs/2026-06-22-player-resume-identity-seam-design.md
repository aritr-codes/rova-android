# Player resume-from-position — shared canonical-identity seam (SPEC-ONLY)

**Date:** 2026-06-22
**Track:** B (player). **This cycle = SPEC ONLY. No code.** Groundwork (`ResumePolicy`, `LibraryMetadataEntry.positionMs` codec) already landed in #127.
**Goal:** Define the single canonical identity that lets the Player and the Library agree on *which recording a saved playback position belongs to*, tier-independently and prune-safely — so resume-from-position can be implemented later without an identity mismatch.

## The problem (verified)

| Side | How it identifies a recording |
|---|---|
| Library write/read of metadata | `VideoItem.stableKey` = `file.absolutePath` (TIER1/2/3) · `docUri.toString()` (SAF) · `"session:$sessionId"` (fallback) |
| Player | launched by `sessionId` → loads `SessionManifest` → `PlayerUriResolver.resolve` returns a **tier-dependent** playable URI (TIER1 = `content://media/...` ≠ the `_DATA` path; TIER2/3 = `file://`; SAF = docUri; vault = `vaultfile://`) |

So a position the Library would key under `absolutePath` cannot be found by the Player, which only has `sessionId` → a `content://` URI. Worse, `LibraryMetadataStore.prune(keep)` drops any key not in the *currently-visible* refresh set; a TIER1 row whose MediaStore `_DATA` query momentarily returns null (scan delay / pending row) leaves the visible set and its metadata — favorite, title, AND position — is pruned.

## Decision: canonical identity = `sessionId` (codex-reviewed 2026-06-22)

For every recording that resume can apply to, `sessionId` is present on both sides (the player launches by it; the library row carries it for finalized exports; a row with no `sessionId` can't launch the player at all). Keying by `sessionId` makes the playable-URI tier (`content://` vs `_DATA`) **irrelevant to identity** — we never key by URI for manifest-backed rows.

> **Deliberate divergence from the kickoff brief:** the brief anticipated a path-canonical design needing "a JVM test pinning the TIER1 `content://`→`_DATA` mapping." Under sessionId-canonical that mapping is unnecessary. The equivalent guard becomes: *a TIER1 `content://` row and the same recording's library `_DATA` path resolve to the SAME canonical session key.* This was raised with the owner; sessionId-canonical was chosen for being tier-independent and not depending on the fragile/deprecated `_DATA` query (the very query that causes the orphan-prune).

codex rejected the path-canonical alternative (Design B): it hard-depends on `_DATA` (deprecated; can change) and MediaStore scan timing, and does not fix prune safety on its own.

## Seam design

### 1. Pure `RecordingIdentity` helper (the seam)

Compose/Android-free, JVM-testable. Used by BOTH the Library and the Player so they cannot drift:

```kotlin
object RecordingIdentity {
    /** Session-level metadata key (favorite, customTitle, lastPlayedAt). NO side suffix. */
    fun sessionKey(sessionId: String): String = "session:$sessionId"

    /** Legacy alias a pre-seam sidecar entry may still be stored under, for dual-read fallback. */
    fun legacyKey(file: File?, docUri: String?): String? =
        file?.absolutePath ?: docUri

    /** Per-side playback position is sub-keyed INSIDE the session entry by side (see §2), not by
        a side-suffixed top-level key — favorite/title stay session-level. */
}
```

### 2. Entry model split — session-level vs per-side (codex's key catch)

`favorite` / `customTitle` / `lastPlayedAt` are **session-level**; a resume `positionMs` is **per-side** (DualShot P+L has two streams with independent durations). Do NOT fragment session-level fields under a side-suffixed key. Instead carry position per side *inside* the session entry:

```kotlin
data class LibraryMetadataEntry(
    val favorite: Boolean = false,
    val customTitle: String? = null,
    val lastPlayedAt: Long? = null,
    val positionsBySide: Map<String, Long> = emptyMap(), // "" = single; "PORTRAIT"/"LANDSCAPE" = P+L
) {
    fun isEmpty() = !favorite && customTitle == null && lastPlayedAt == null && positionsBySide.isEmpty()
}
```

- Codec migration is **forward-compatible**: read a legacy flat `positionMs` as `positionsBySide[""] = it`. Persist only positions `> 0`.
- A `sideSlot(side: VideoSide?): String` helper (`""` / `"PORTRAIT"` / `"LANDSCAPE"`) lives in `RecordingIdentity`.

> **Note vs #127:** #127 added a flat `positionMs`. This entry-model split is part of Track B's *implementation* cycle, not now — recorded here so the eventual plan knows the flat field is interim.

### 3. Lazy dual-read migration (NO destructive rewrite)

A botched eager migration could lose user favorites (shared namespace). Instead:

- **Read:** `store.get(sessionKey)` first; if absent, fall back to `store.get(legacyKey)`.
- **Write:** always write the `sessionKey`. On the first write for a recording that had a legacy entry, **merge** then remove the legacy key — merge rule: canonical wins per-field; import only fields the canonical entry is missing. Be explicit about `favorite=false` vs field-absent so a fallback never silently un-favorites.
- Sessionless imported rows (no manifest) keep their `legacyKey` and are **never** converted.

### 4. Prune fix — keep-set from durable manifests, not visible rows

`prune` must keep:
1. `sessionKey` for **every known finalized manifest** (durable — independent of whether the row resolved this refresh),
2. `legacyKey` for **visible** sessionless/imported rows,
3. (grace) legacy aliases still awaiting dual-read migration.

A momentarily-unresolvable TIER1 row therefore retains its position. Bounded growth: session keys are cleaned when their manifest is deleted (existing recovery/delete paths already remove manifests) — do NOT retain historical path keys forever.

### 5. Player wiring (deferred to implementation)

`PlayerViewModel`, on open: read `positionsBySide[sideSlot(side)]` via `RecordingIdentity.sessionKey(sessionId)`, apply `ResumePolicy.resolveOpenPosition(saved, duration)`, seek. On pause / dispose: write the current position back under the same key+side. (Wiring + the save trigger are the implementation cycle, not this spec.)

## JVM tests to pin (implementation cycle)

1. TIER1 `content://` row and the same recording's library `_DATA` path resolve to the **same** `sessionKey`.
2. A temporarily invisible/unresolved MediaStore row does **not** prune its session metadata (prune keep-set includes its manifest sessionKey).
3. Legacy path-key metadata is read via fallback and rewritten to canonical on next save; legacy key removed.
4. Conflict merge when both canonical and legacy entries exist (canonical wins; missing fields imported; favorite not lost).
5. P+L sides do not overwrite each other's position (`positionsBySide`).
6. Deleting a manifest eventually prunes its session keys.
7. Sessionless imports keep path/docUri keys and are never auto-converted.
8. Codec round-trips legacy flat `positionMs` → `positionsBySide[""]`.

## Files (implementation cycle — for reference, NOT this PR)

- **new** `ui/library/RecordingIdentity.kt` (+ test)
- `ui/library/LibraryMetadataEntry.kt` + `LibraryMetadataCodec.kt` (entry-model split, legacy read)
- `ui/library/LibraryMetadataStore.kt` (dual-read, merge-on-write)
- `ui/screens/HistoryViewModel.kt` (`stableKey` → `RecordingIdentity`; prune keep-set from all manifests)
- `ui/screens/player/PlayerViewModel.kt` (read/apply/save position)

## Out of scope this cycle

All code. PR-6b wall-clock playhead (needs a manifest schema bump for per-segment wall-start). PR-7 speed / double-tap / auto-hide.
