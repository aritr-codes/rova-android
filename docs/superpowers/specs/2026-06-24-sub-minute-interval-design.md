# Sub-minute recording interval (30s) — design spec

**Status**: Design — owner-ratified decisions, codex-reviewed, awaiting spec sign-off
**Date**: 2026-06-24
**Branch**: `feat/sub-minute-interval`, **stacked on** `feat/player-wall-clock-playhead` (PR-6b). Schema goes 11→12 (PR-6b) → **13** (this).
**Goal**: Let the recording-interval picker offer **30 s** below the current 1-minute floor.

---

## 1. Problem + decision

The recording interval (idle WAIT between clips) is modeled as `intervalMinutes: Int` — whole minutes only, so the smallest non-zero wait is 1 min. The owner wants a **30 s** option. `Int` minutes cannot express 30 s.

**Decisions (owner-ratified):**
- **D1 — representation:** migrate the source-of-truth to **`intervalSeconds: Int`** (minutes was a premature unit; the scheduler already works in seconds). Rejected: fractional-minutes `Double` (the `M_MINUTES.toLong()` intent path truncates 0.5→0; ugly display).
- **D2 — minimum:** smallest non-zero interval = **30 s** (15 s dropped — at 15 s the existing audio cues would eat too much of the gap; 30 s accommodates them, so **no cue changes in this PR**).
- **D3 — picker:** stepper over an ordered **allowed-values list** `[0, 30, 60, 120, 180, …, 3600]` (0 = None/Continuous, then 30 s, then 1-min steps to 60 min). Steps by index; direct/raw set paths **snap to nearest allowed** (codex #7).
- **D4 — sequencing:** **stack on PR-6b** → schema **13**, single combined-smoke APK (codex #3).
- **D5 — ADR:** new **ADR-0033** (interval unit minutes→seconds + 30 s min).

Confirmed from the code map: interval is end-to-start **idle wait** (not start-to-start period), so **no interval-vs-duration guard** is needed; `duration=60s, interval=30s` = record ~60 s, idle 30 s, repeat.

---

## 2. The migration surfaces (3 + intent — codex #1/#2/#4)

A unit change touches **every** place the value is persisted or crosses a process boundary. All must migrate, lossless, old data still loads:

### 2.1 Manifest schema (`SessionManifest.SessionConfig`)
- Field `intervalMinutes: Int` → `intervalSeconds: Int`.
- `toJson`: `put("intervalSeconds", intervalSeconds)`.
- `fromJson`: `intervalSeconds = if (json.has("intervalSeconds")) json.getInt("intervalSeconds") else json.getInt("intervalMinutes") * 60` — old manifests (no `intervalSeconds` key) read the legacy minutes key ×60. Lossless.
- `SCHEMA_VERSION` **12 → 13** (PR-6b set 12), add `12->13` history comment.
- Display of OLD recordings stays correct (5 min → 300 s → "5 m").

### 2.2 `RovaSettings` (SharedPreferences) — codex #1
- The current key is `"interval"` storing **minutes**. **Do NOT reinterpret it** — an existing user's stored `5` would become 5 **seconds**.
- Add a **new** key `"interval_seconds"`. On read: if `interval_seconds` present → use it; else one-shot migrate `getInt("interval", 1) * 60` and write it to the new key (so the migration happens once). Default `60` (s).
- The old `"interval"` key may be left dormant (no harm) or removed after migration; leave it dormant for safety.

### 2.3 `PresetJson` / built-in + custom presets — codex #2
- `BuiltInPresets` interval values ×60: Quick Sample 1→**60 s**, Standard 2→**120 s**, Long Session 5→**300 s**, Continuous 0→**0**.
- `PresetJson` (custom-preset persistence, currently `VERSION = 2`, interval in minutes): bump to **3**; decode legacy v2 `interval * 60` on read; encode seconds on write.
- **Custom-preset ID hashing:** if the generated custom-preset ID hashes the interval value, converting minutes→seconds would change the hash → legacy custom presets could get NEW ids / mismatch `activePresetId`. Verify `PresetMatcher`/ID derivation; if the interval feeds the hash, either (a) hash on the canonical seconds value consistently after migrating stored presets, or (b) keep IDs stable by deriving from the migrated value. Plan-stage must read the ID derivation and pin a test.

### 2.4 Intent extra (UI → service boundary) — codex #4
- Add extra `M_SECONDS` (Int). Service startup: `if (intent.hasExtra("M_SECONDS")) intent.getIntExtra("M_SECONDS", 60) else intent.getFloatExtra("M_MINUTES", 1f).toInt() * 60` — tolerate an in-flight old `M_MINUTES` start (process death / stale explicit start). TICK PendingIntents don't carry the interval, so risk is low, but the fallback is cheap.

---

## 3. Scheduler + plumbing

- `mMinutes: Long` → `mSeconds: Long`. Set from `intervalSeconds` (no `.toFloat()` dance) and from the `M_SECONDS` extra.
- `waitSeconds = mSeconds.toInt().coerceAtLeast(0)` — **drop the `*60`** (`RovaRecordingService.kt:1573`). `triggerAt = now + waitSeconds*1000L` and `delay(waitSeconds*1000L)` unchanged.
- 15 s was the alarm-reliability worry; at 30 s min, the in-process `delay` + held wakelock during the live FGS is the practical path (codex #5 — don't promise exact-alarm delivery under Doze, but recording keeps the loop awake). No change to the alarm mechanism.
- `BeepPolicy` receives the interval; it only checks `== 0` (Continuous → no beep), which still holds for seconds. **Plan-stage must confirm** `BeepPolicy` does no minute-arithmetic beyond `== 0` (codex #6); at 30 s min no cue change is required regardless.

---

## 4. Picker (stepper) + formatters

### 4.1 `RecordSettingBounds` — allowed-values stepper
- Replace the `WAIT_MIN=0..WAIT_MAX=60` arithmetic `stepWait(+1)` with an ordered list:
  `val WAIT_ALLOWED = listOf(0, 30) + (1..60).map { it * 60 }`  // [0,30,60,120,…,3600]
- `stepWait(current, dir)` → find current's index in `WAIT_ALLOWED` (via `nearestAllowedWait` if not exact), move ±1, clamp to list bounds.
- `nearestAllowedWait(value): Int` → the allowed value closest to `value` (snaps stray direct-set values — codex #7).
- Pure, JVM-tested.

### 4.2 Formatters (`RecordSettingsFormat.recordWaitValue`, `LibrarySessionConfigFormatters.formatWait`)
- Seconds-aware: `0 → "None"`, `<60 → "30 s"` (resource), `==60 → "1 m"`/"1 min", `%60==0 → "N m"`/"N min", `==3600 → "1 h"`, `%3600==0 → "N h"`. The allowed set never yields a mixed "1 m 30 s", but keep the `else` defensive (`"Xm Ys"`).
- New string resources for the seconds case (en + es): e.g. `record_wait_seconds` = `"%1$d s"`. Existing minute/hour strings reused.

---

## 5. ADR-0033

`docs/adr/0033-interval-seconds-unit.md`: Context (sub-minute interval need), Decision (`intervalSeconds: Int`, 30 s minimum non-zero, allowed-values stepper, schema 12→13 with legacy `intervalMinutes*60` read, prefs + preset migrations), Consequences (old manifests/prefs/presets migrate losslessly; no interval-vs-duration guard; cue behavior unchanged at 30 s). Amend-first: lands before code.

---

## 6. Tests (same PR, JVM)

1. **Manifest**: `intervalSeconds` round-trips; legacy JSON with `intervalMinutes` only → `intervalSeconds = minutes*60`; byte-shape writes `intervalSeconds`.
2. **Schema version**: `SCHEMA_VERSION == 13` (update the existing version-pinning tests).
3. **RovaSettings migration**: a prefs fixture with old `"interval"=5` and no `"interval_seconds"` → reads 300; subsequent reads use the new key; default with no keys → 60.
4. **PresetJson**: legacy v2 preset (interval minutes) decodes to seconds; built-in preset values are the ×60 seconds; custom-preset ID stability across the migration (per §2.3 finding).
5. **Stepper**: `WAIT_ALLOWED` ordering; `stepWait` up/down across the 30 s↔1 m boundary and at list ends; `nearestAllowedWait(45) == 30`, `nearestAllowedWait(50) == 60`, `nearestAllowedWait(10) == 0`-or-`30` (define + test the tie/threshold).
6. **Formatters**: `recordWaitValue`/`formatWait` for 0/30/60/120/3600.
7. **Scheduler conversion** (if a pure seam exists or one is extracted): `intervalSeconds → waitSeconds` is identity (no ×60); 0 → 0.

---

## 7. Global constraints

- 46 gates + full `:app:testDebugUnitTest` GREEN every commit; verify via `:app:assembleDebug` (not `:app:lintDebug`).
- No `check*` edits (none asserts the interval unit). `checkNoHardcodedUiStrings` → new strings en + es.
- Pure-helper extraction (stepper, formatters, any scheduler seam).
- ADR-0033 lands before code.
- Subagents EDIT-ONLY; controller runs all gradle/tests/commits. Build WARM. Device smoke on RZCYA1VBQ2H.
- Stacked on PR-6b → schema 13; merge train PR-6b → intervals. Push/PR/merge only on owner GO.

## 8. Out of scope (YAGNI)

- No 15 s (or smaller) intervals; no cue-timing rework (deferred — 30 s accommodates cues).
- No interval-vs-duration validation.
- No change to the alarm/Doze mechanism.
- No new picker UX beyond the stepper allowed-values list (no free-text entry).
