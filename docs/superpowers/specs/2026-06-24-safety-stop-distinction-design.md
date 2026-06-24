# Safety-stop distinction — design

**Date:** 2026-06-24
**Branch:** `feat/safety-stop-distinction` (off master `29be01d`)
**Type:** Display-layer only. No schema change, no terminal-classification change.
**ADRs touched:** ADR-0016 (thermal autostop), ADR-0027 (daily window), ADR-0030 (Library badges) — presentation clauses, amend-first.
**Codex:** reviewed (thread `019ef96e`). Recommendations adopted; one ordering correction applied (see §3).

## 1. Problem

Thermal, low-storage, and scheduled-window auto-stops are persisted as
`Terminated.USER_STOPPED` with a **distinct** `SessionManifest.stopReason`
(`THERMAL` / `LOW_STORAGE` / `SCHEDULE_WINDOW`), written atomically (the eager-write
contract). The terminal classification is correct and must stay unchanged.

Both UI consumers ignore `stopReason` today, so an auto-stop is indistinguishable
from a manual stop:

- **Library** — `StatusBadgePolicy.badgeFor(terminated, exportState)` maps
  `USER_STOPPED → null` (no badge). A thermal cut shows the same bare row as a
  clean manual stop.
- **Recovery card** — `RecoveryUiStateMapper.toCard` derives `RecoveryCardKind`
  from `Terminated` only; every gate-stop collapses into `RecoveryCardKind.USER_STOPPED`
  and renders the generic "you stopped this recording" copy.

This is a presentation-layer bug. The data to fix it already exists.

## 2. Agreed taxonomy

| StopReason (on `USER_STOPPED`) | Category | Library | Recovery copy/accent |
|---|---|---|---|
| `THERMAL` | safety | `AUTO_STOPPED` badge | "cool down" copy, amber |
| `LOW_STORAGE` | safety | `AUTO_STOPPED` badge | "storage full" copy, amber |
| `SCHEDULE_WINDOW` | scheduled | **no badge** (planned success) | "ended on schedule", **neutral** |
| `PERMISSION_REVOKED` | error | `INTERRUPTED` badge | "interrupted" copy, amber |
| `INIT_FAILED` | error | `INTERRUPTED` badge | "interrupted" copy, amber |
| `USER` / `NONE` | manual | no badge | "you stopped" copy, amber |

Plus the non-`USER_STOPPED` terminals (unchanged): `KILLED_BY_SYSTEM`
(hard-red + vendor slot + pulse), `KILLED_FORCE_STOP`, `COMPLETED`,
`MULTI_SEGMENT_KEPT` (→ Recovered).

## 3. Shared pure helper — taxonomy SSOT

Single Compose/Android-free helper consumed by **both** surfaces, JVM-tested once.
Lives in `data/` (alongside `SessionManifest`) so both `ui/library` and `ui/recovery`
depend on it without a cross-UI dependency.

```kotlin
enum class StopCategory {
    COMPLETED, USER_STOPPED, SAFETY_STOPPED, SCHEDULED_END,
    ERROR_STOPPED, INTERRUPTED, RECOVERED
}

object StopCategoryClassifier {
    fun categorize(
        terminated: Terminated?,
        stopReason: StopReason,
        exportState: ExportState,
    ): StopCategory = when {
        terminated == Terminated.MULTI_SEGMENT_KEPT -> StopCategory.RECOVERED
        terminated == Terminated.KILLED_BY_SYSTEM ||
            terminated == Terminated.KILLED_FORCE_STOP -> StopCategory.INTERRUPTED
        terminated == Terminated.USER_STOPPED &&
            (stopReason == StopReason.THERMAL ||
             stopReason == StopReason.LOW_STORAGE) -> StopCategory.SAFETY_STOPPED
        terminated == Terminated.USER_STOPPED &&
            stopReason == StopReason.SCHEDULE_WINDOW -> StopCategory.SCHEDULED_END
        terminated == Terminated.USER_STOPPED &&
            (stopReason == StopReason.PERMISSION_REVOKED ||
             stopReason == StopReason.INIT_FAILED) -> StopCategory.ERROR_STOPPED
        exportState == ExportState.FAILED -> StopCategory.INTERRUPTED   // see precedence note
        terminated == Terminated.COMPLETED -> StopCategory.COMPLETED
        terminated == Terminated.USER_STOPPED -> StopCategory.USER_STOPPED   // USER / NONE
        else -> StopCategory.COMPLETED   // terminated == null → no badge
    }
}
```

### Precedence note (codex correction)

Codex proposed `COMPLETED → COMPLETED` *before* the `FAILED` check. That breaks the
existing load-bearing test `badgeFor(COMPLETED, FAILED) == INTERRUPTED`. The
`exportState == FAILED` branch therefore runs **after** the specific gate-reason
splits (so a thermal-stop-with-failed-export still reads `SAFETY_STOPPED` — specific
cause wins) but **before** `COMPLETED` (so a completed-but-failed-export still reads
`INTERRUPTED`). Codex's core principle — a persisted `StopReason` must beat a generic
export failure — is preserved.

`SCHEDULE_WINDOW` gets an **explicit** branch (rule 4), never reached through a broad
"not-user" fallthrough — codex's flagged risk that a scheduled end could inherit amber
severity.

## 4. Library mapping

`StatusBadgePolicy.badgeFor` gains a `stopReason` parameter and delegates to the helper:

```kotlin
fun badgeFor(terminated: Terminated?, stopReason: StopReason, exportState: ExportState): LibraryBadge? =
    when (StopCategoryClassifier.categorize(terminated, stopReason, exportState)) {
        StopCategory.SAFETY_STOPPED -> LibraryBadge.AUTO_STOPPED
        StopCategory.INTERRUPTED, StopCategory.ERROR_STOPPED -> LibraryBadge.INTERRUPTED
        StopCategory.RECOVERED -> LibraryBadge.RECOVERED
        StopCategory.SCHEDULED_END, StopCategory.USER_STOPPED, StopCategory.COMPLETED -> null
    }
```

### New badge value
```kotlin
enum class LibraryBadge { RECOVERED, INTERRUPTED, AUTO_STOPPED }   // +1
```

### Glyph + color (no new color, no new glyph asset)
`AUTO_STOPPED` reuses the **locked `IconStatus.Interrupted` orange** (owner pick).
Glyph reuses the existing `RovaIcons.Thermal` / `RovaIcons.Storage` glyphs, selected
by the underlying gate reason (thermal → thermometer, storage → storage), each wrapped
with `IconStatus.Interrupted`. Because `LibraryIconSpec.badgeGlyph(badge)` is enum-only
today, the glyph picker must become reason-aware:

- **Decision (impl):** thread the gate reason into the badge render. `LibraryRow` carries
  a nullable `autoStopGlyphKind` (THERMAL/STORAGE) set only when `badge == AUTO_STOPPED`;
  `badgeGlyph` overload picks `Thermal` vs `Storage`. Falls back to `Thermal` if absent.
  This stays pure and JVM-testable. The `when` over `LibraryBadge` stays exhaustive
  (compile-forced) — `checkStatusColorLocked` clean (no `.copy`), no new locked color.

### Label
New string `library_badge_auto_stopped` ("Auto-stopped", es: "Detención automática"),
routed through `statusBadgeLabel(badge, recovered, interrupted, autoStopped)`. Must dodge
`checkUserCopyVocabulary` (no loop/repeat/segment).

### Plumbing
`LibraryRowMapper.Input` gains `stopReason: StopReason`; the ViewModel that builds `Input`
reads `manifest.stopReason`. All 6 existing `StatusBadgePolicyTest` cases stay green (call
sites pass `StopReason.USER`/`NONE`); new cases cover SAFETY/SCHEDULED/ERROR.

## 5. Recovery mapping

Per codex: **expand `RecoveryCardKind`** (single enum, no orthogonal field — avoids
inconsistent kind/category pairs):

```kotlin
enum class RecoveryCardKind {
    USER_STOPPED, SAFETY_STOPPED, SCHEDULED_END, ERROR_STOPPED,
    KILLED_BY_SYSTEM, KILLED_FORCE_STOP
}
```

`RecoveryUiStateMapper.toCard` now reads `manifest.stopReason`. For `Terminated.USER_STOPPED`
it splits by `stopReason` into the four new kinds; `KILLED_*` map directly (unchanged).
`RECOVERED`/`COMPLETED` `StopCategory` values have no recovery card (already filtered by
`isEligible`). Mapping helper reuses the SSOT classifier for the USER_STOPPED split, then
distinguishes `KILLED_BY_SYSTEM` vs `KILLED_FORCE_STOP` from `Terminated` directly (the
`INTERRUPTED` category collapses them but the card needs the split for the vendor slot).

### Per-kind treatment (all `when` exhaustive — compile-forced, existing pattern)

| Kind | Title/body (per-reason copy) | Severity accent | Vendor slot | Pulse |
|---|---|---|---|---|
| `USER_STOPPED` | "You stopped this recording" (existing) | soft amber | no | no |
| `SAFETY_STOPPED` | THERMAL: "Paused to cool down" · LOW_STORAGE: "Stopped — storage was full" | soft amber | no | no |
| `SCHEDULED_END` | "Ended on schedule" | **neutral** (new non-amber accent token) | no | no |
| `ERROR_STOPPED` | "Recording was interrupted" (PERMISSION/INIT) | soft amber | no | no |
| `KILLED_BY_SYSTEM` | existing | hard red | yes | yes |
| `KILLED_FORCE_STOP` | existing | soft amber | no | no |

`SAFETY_STOPPED` needs per-reason title/body, so the card must carry the gate reason
(THERMAL vs LOW_STORAGE) — `RecoveryCardState` gains the already-resolved `@StringRes`
title/body (the mapper picks them), so the composable stays reason-agnostic. The mapper
reads `stopReason` to choose between the thermal and storage string ids.

`severityColorFor` and `RecoveryIconSpec.statusGlyphFor` gain the new kinds:
- `SAFETY_STOPPED`/`ERROR_STOPPED` → amber (`RovaWarnings.soft`); `SCHEDULED_END` →
  neutral. The neutral accent is **not** a locked `RovaSemantics` status color — it reuses
  an existing `RovaWarnings`/quiet token (the card glow/tag already use `RovaWarnings`, not
  the locked palette, so no `checkStatusColorLocked` exposure).
- `RecoveryIconSpec.statusGlyphFor`: `SAFETY_STOPPED` → `RovaIcons.Thermal`/`Storage`
  (or keep generic Recovery emblem — impl choice, default generic to limit surface);
  `SCHEDULED_END`/`ERROR_STOPPED`/`USER_STOPPED` → null (generic emblem). `KILLED_*` →
  `RovaIcons.Interrupted` (unchanged).

### `RecoveryScanner` — no change
The scanner's classification (`Terminated.USER_STOPPED` for `stopRequested`,
`KILLED_FORCE_STOP` otherwise) is correct and untouched. `stopReason` is descriptive and
already persisted; the scanner does not (and must not) read it for classification —
preserves `checkUserStoppedBeforeMerge` and `checkRecoveryNoDeletion`. The presentation
mapper is the only new reader.

## 6. Strings (en + es) — all dodge `checkUserCopyVocabulary`

- `library_badge_auto_stopped`
- `recovery_title_safety_thermal`, `recovery_body_safety_thermal`
- `recovery_title_safety_storage`, `recovery_body_safety_storage`
- `recovery_title_scheduled`, `recovery_body_scheduled`
- `recovery_title_error`, `recovery_body_error`
- `recovery_tag_*` for the new kinds (severity tag chip)

Existing `recovery_title_user_stopped` / `recovery_body_user_stopped` reused for the
manual `USER_STOPPED` kind.

## 7. Tests (JVM, same PR)

- `StopCategoryClassifierTest` — full precedence matrix incl. the FAILED-vs-stop-reason
  ordering, SCHEDULE neutral branch, null-terminated.
- `StatusBadgePolicyTest` — 6 existing stay green; add SAFETY→AUTO_STOPPED,
  SCHEDULED→null, ERROR→INTERRUPTED, and FAILED-over-completed retained.
- `RecoveryUiStateMapperTest` — new: each StopReason on `USER_STOPPED` maps to the right
  kind + title/body res; SCHEDULED neutral; KILLED_* unchanged; vendor slot only for
  KILLED_BY_SYSTEM.
- Glyph picker test (`LibraryIconSpec`) — AUTO_STOPPED reason→glyph; status =
  `IconStatus.Interrupted`.

## 8. Gates / constraints

- `checkStatusColorLocked` — reuse `IconStatus.Interrupted`, no `.copy`. Clean.
- `checkUserCopyVocabulary` — new strings avoid loop/repeat/segment (en+es). Verify.
- `checkNoHardcodedUiStrings` — all new copy in resources, en+es.
- `checkUserStoppedBeforeMerge`, `checkRecoveryNoDeletion` — scanner untouched. Clean.
- Verify via `:app:assembleDebug` (fires 46 gates on preBuild), **not** `:app:lintDebug`
  (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- Full `:app:testDebugUnitTest` green at every commit.
- Device smoke on RZCYA1VBQ2H before owner GO: trigger a thermal/storage auto-stop (or
  inject) and confirm the Library badge + recovery card read as auto-stopped; confirm a
  manual stop still reads clean; confirm a scheduled end reads neutral.

## 9. Out of scope (YAGNI)

- No new locked status color.
- No change to terminal classification, scanner, or schema.
- No Library badge for SCHEDULED_END (planned success ≠ exceptional, ADR-0030).
- `MULTI_SEGMENT_KEPT` / `COMPLETED` recovery behavior unchanged.
