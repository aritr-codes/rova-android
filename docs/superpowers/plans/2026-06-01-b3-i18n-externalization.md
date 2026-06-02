# B3 Phase A — i18n String Externalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every user-facing string into `res/values/strings.xml`/`plurals.xml` with stable names, behind a new `checkNoHardcodedUiStrings` static gate, with zero runtime/behaviour change.

**Architecture:** Pure resource indirection. Compose call sites use `stringResource`/`pluralStringResource`; service/notification (has `Context`) use `getString`/`getQuantityString`; pure helpers/ViewModels return `@StringRes Int` or a new pure `UiText` token resolved at each edge. A regex `check*` Gradle task wired into `preBuild` prevents regressions. No translation, no locale picker (that is Phase B, a later slice).

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (BOM 2025.01.01), AGP 9.2.1, Gradle 9.4.1, JUnit JVM tests (`isReturnDefaultValues = true`), `org.json` on `testImplementation`.

---

## Spec

Source of truth: `docs/superpowers/specs/2026-06-01-b3-i18n-externalization-design.md`. Read the **Runtime-behaviour hazards** section before any sweep task.

## Conventions every sweep task follows

**Resource naming** — domain prefix: `record_*`, `history_*`, `settings_*`, `player_*`, `warning_*`, `recovery_*`, `onboarding_*` (existing), `notification_*` (existing), `common_*` (shared ≥2 domains). Name by meaning, not by English words (`history_action_open`, not `history_open_text`).

**The transform (Compose, fixed text):**
```kotlin
// before
Text("Start Recording")
// strings.xml
<string name="record_start_button">Start Recording</string>
// after
Text(stringResource(R.string.record_start_button))
```

**The transform (Compose, with args):**
```kotlin
// before
Text("${selectedFiles.size} selected")
// plurals.xml  (count drives quantity selection)
<plurals name="history_selected_count">
    <item quantity="one">%d selected</item>
    <item quantity="other">%d selected</item>
</plurals>
// after  (count passed TWICE: rule selection + %d fill)
Text(pluralStringResource(R.plurals.history_selected_count, selectedFiles.size, selectedFiles.size))
```

**The transform (contentDescription):**
```kotlin
// before
decreaseDescription = "Decrease clip length by 5 seconds"
// strings.xml
<string name="record_stepper_decrease_cd">Decrease clip length by 5 seconds</string>
// after
decreaseDescription = stringResource(R.string.record_stepper_decrease_cd)
```

**Escaping (mandatory — silent-behaviour-change hazards):** in XML values escape `'`→`\'`, `\`→`\\`, `&`→`&amp;`, `<`→`&lt;`. A literal `%` in a string that takes format args must be `%%` (or set `formatted="false"`). Preserve existing `%1$d`/`%1$s` specifiers exactly.

**`R` import:** add `import com.aritr.rova.R` to any file that gains a resource reference and does not already import it.

**Per-file verification (every sweep task):** after editing, the file has no remaining flaggable literal. Verify with Grep (not a behaviour test — the contract is *no change*):
```
Grep  pattern: Text\(\s*"|contentDescription\s*=\s*"|label\s*=\s*"
      path:    <the file>      → expect: no matches (or only i18n-opt-out: lines)
```
Then the build must compile and the full suite stays green (see commands below).

**Gradle commands (Windows / PowerShell tool — gradle is routed through the allowed PowerShell path, never the Bash gradle hook):**
```powershell
& ".\gradlew.bat" :app:compileDebugKotlin            # fast compile gate per sweep task
& ".\gradlew.bat" :app:testDebugUnitTest             # full JVM suite — must stay 1377/0-0-0
```
Expected after every task: `BUILD SUCCESSFUL`, test suite unchanged at **1377 / 0 failures / 0 errors**. No new tests are added by sweep tasks (behaviour is unchanged); Tasks 1 and 11 add tests.

**Do NOT externalize (leave literal):** `RovaLog.*`/`Log.*`, `throw …Exception("…")`, `BuildConfig`, analytics/route/destination keys, MIME/extensions, `@Preview` sample data, test sources. If a flagged literal is one of these, append `// i18n-opt-out: <reason>` to its line.

---

### Task 1: `UiText` seam + resolution edges

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/text/UiText.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/text/UiTextTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
        assertEquals(UiText.Plural(1, 2, listOf(2)), UiText.Plural(1, 2, listOf(2)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.aritr.rova.ui.text.UiTextTest"
```
Expected: FAIL — `Unresolved reference: UiText`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.aritr.rova.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A resource-backed, framework-free text token (ADR-0022).
 *
 * Lets pure helpers / ViewModels that own arguments or plural counts decide
 * *which* copy to show without resolving (and thus freezing) localized English —
 * Compose and the service each resolve the same token at their own edge. A bare
 * `@StringRes Int` is preferred when a helper only selects fixed copy; reach for
 * `UiText` only when args or plurals are involved.
 */
sealed interface UiText {
    data class Str(@StringRes val id: Int) : UiText
    data class StrArgs(@StringRes val id: Int, val args: List<Any>) : UiText
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Str -> stringResource(id)
    is UiText.StrArgs -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *args.toTypedArray())
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Str -> context.getString(id)
    is UiText.StrArgs -> context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, quantity, *args.toTypedArray())
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.aritr.rova.ui.text.UiTextTest"
```
Expected: PASS (4 tests). The `@Composable resolve()` is compiled but not unit-tested (composable; covered by device smoke).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui/text/UiText.kt app/src/test/java/com/aritr/rova/ui/text/UiTextTest.kt
git commit -m "feat(i18n): UiText resource-backed text token + resolution edges (ADR-0022)"
```

---

### Task 2: Record screen / chrome / HUD literals

**Files (externalize every flaggable literal in each):**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/BackgroundRecordingBanner.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/LargeValueStepper.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/QuickSetChipRow.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate literals in scope**

For each file run:
```
Grep  pattern: Text\(\s*"|contentDescription\s*=\s*"|label\s*=\s*"|Description\s*=\s*"
      path: <file>  -n: true  output_mode: content
```
List each user-facing literal. Skip `RovaLog.`/`Log.`/exception/`BuildConfig`/`@Preview` lines.

- [ ] **Step 2: Add `record_*` resources**

Add one `<string name="record_…">` per literal to `strings.xml` (apply escaping rules). Example block:
```xml
<!-- Record screen -->
<string name="record_start_button">Start Recording</string>
<string name="record_stepper_decrease_cd">Decrease clip length by 5 seconds</string>
<string name="record_stepper_increase_cd">Increase clip length by 5 seconds</string>
```
Any count-based copy → `plurals.xml` (created in Task 3 if not yet present; if needed here, create it now with the `<resources>` wrapper).

- [ ] **Step 3: Replace call sites**

Replace each literal with `stringResource(R.string.record_…)` (or `pluralStringResource(...)`). Add `import com.aritr.rova.R` if missing. `contentDescription` inside non-`@Composable` builders that lack a Compose scope: pass `@StringRes Int` and resolve at the composable, per the spec resolution table.

- [ ] **Step 4: Verify no literals remain + build green**

```
Grep  pattern: Text\(\s*"|contentDescription\s*=\s*"|label\s*=\s*"   path: <each file>   → no user-facing matches
```
```powershell
& ".\gradlew.bat" :app:compileDebugKotlin
& ".\gradlew.bat" :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`; suite **1377/0-0-0**.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/ui app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize Record screen/chrome/HUD strings"
```

---

### Task 3: History / Library literals

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/LibrarySessionConfigDialog.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create (if not present): `app/src/main/res/values/plurals.xml`

- [ ] **Step 1: Enumerate** (same Grep as Task 2, on these files). Known literals include: `"Open device settings"`, `"${selectedFiles.size} selected"` (→ plural), `"Library"`, `"Open"`, `"Edit"`, `"View Settings"`, `"Start Recording"` (reuse `record_start_button` via `common_*` if shared — see DRY note).

- [ ] **Step 2: Add resources**

`strings.xml`:
```xml
<!-- History / Library -->
<string name="history_title">Library</string>
<string name="history_action_open">Open</string>
<string name="history_action_edit">Edit</string>
<string name="history_action_view_settings">View Settings</string>
<string name="history_open_device_settings">Open device settings</string>
```
`plurals.xml` (create with full wrapper if new):
```xml
<resources>
    <plurals name="history_selected_count">
        <item quantity="one">%d selected</item>
        <item quantity="other">%d selected</item>
    </plurals>
</resources>
```
DRY: a label identical to one already in `common_*`/another domain and semantically the same → reuse it; do not add a duplicate value.

- [ ] **Step 3: Replace call sites**
```kotlin
title = { Text(pluralStringResource(R.plurals.history_selected_count, selectedFiles.size, selectedFiles.size)) }
Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
text = { Text(stringResource(R.string.history_action_open)) }
```

- [ ] **Step 4: Verify + build** (same as Task 2 Step 4, on these files).

- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui app/src/main/res/values
git commit -m "feat(i18n): externalize History/Library strings + selected-count plural"
```

---

### Task 4: Settings screen + sheets literals

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsStepperSheet.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/EditSheetShell.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/FixedContinuousSelector.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep on each file). Known: EditSheetShell `"How many clips this session captures."`, FixedContinuousSelector segment labels.

- [ ] **Step 2: Add `settings_*` resources**, e.g.:
```xml
<!-- Settings -->
<string name="settings_clips_help">How many clips this session captures.</string>
```

- [ ] **Step 3: Replace call sites** with `stringResource(R.string.settings_…)`; add `R` import.

- [ ] **Step 4: Verify + build** (Task 2 Step 4 on these files).

- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize Settings screen + sheet strings"
```

---

### Task 5: Player screen literals

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep on PlayerScreen.kt). Note it has 6 hardcoded `contentDescription` literals (transport controls) — externalize all.
- [ ] **Step 2: Add `player_*` resources** (one per literal; transport-control contentDescriptions like `player_play_cd`, `player_pause_cd`, `player_seek_back_cd`, etc.).
- [ ] **Step 3: Replace call sites** with `stringResource(R.string.player_…)`.
- [ ] **Step 4: Verify + build** (Task 2 Step 4 on PlayerScreen.kt).
- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui/screens/player app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize Player screen strings + transport contentDescriptions"
```

---

### Task 6: Onboarding — finish remaining inline literals

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingSlide.kt`
- Modify: any other file under `ui/screens/onboarding/` with flaggable literals
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep `ui/screens/onboarding/`). OnboardingSlide.kt already uses `stringResource` 14× — find any remaining inline `Text("…")`/`contentDescription = "…"` not yet using a resource.
- [ ] **Step 2: Add `onboarding_*` resources** for any gaps (match the existing onboarding copy ban-list comment in `strings.xml`).
- [ ] **Step 3: Replace call sites**.
- [ ] **Step 4: Verify + build** (Task 2 Step 4 on onboarding files).
- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui/screens/onboarding app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize remaining onboarding strings"
```

---

### Task 7: WarningCenter copy (sheets / banners / chips)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/HistoryWarningStrip.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/ThermalTipsSheet.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep each file). Warning copy is prose — watch for apostrophes (`can't`→`can\'t` or `"…"`) and any `%`/`&`.
- [ ] **Step 2: Add `warning_*` resources**. If a warning's copy is *selected* in a non-composable mapper (e.g. a `WarningId → copy` table), have the mapper return `@StringRes Int` (or `UiText` if it carries args) and resolve in the composable — do **not** store resolved English. Example:
```kotlin
// mapper (pure)
@StringRes fun titleResFor(id: WarningId): Int = when (id) { … }
// composable
Text(stringResource(titleResFor(warningId)))
```
- [ ] **Step 3: Replace call sites**.
- [ ] **Step 4: Verify + build** (Task 2 Step 4 on these files).
- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui/warnings app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize WarningCenter sheet/banner/chip copy"
```

---

### Task 8: Recovery copy (`RecoveryCard`, vendor guidance)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/VendorGuidanceIntents.kt` (only user-visible prose; OEM intent action/package constants are NON-user-facing → `i18n-opt-out:`)
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep each file). Distinguish user-visible prose from intent constants/package names (those stay literal with `// i18n-opt-out: OEM intent constant`).
- [ ] **Step 2: Add `recovery_*` resources**.
- [ ] **Step 3: Replace call sites** with `stringResource`/`@StringRes Int` per scope.
- [ ] **Step 4: Verify + build** (Task 2 Step 4; expect remaining matches only on `i18n-opt-out:` lines).
- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui/recovery app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize recovery card + vendor-guidance user copy"
```

---

### Task 9: Notification + service copy

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/notification/` (all files: `NotificationCopy`, `NotificationDotsRow`, builders)
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep these paths). Service/notification has `Context` → use `context.getString(R.string.…)` / `getQuantityString`, NOT `stringResource` (no Compose scope). Some `notification_*` already exist — finish the gaps.
- [ ] **Step 2: Add resources** under the existing `<!-- M5: Notification … -->` groups. Preserve any `%1$d`/`%1$s` specifiers exactly; verify collapsed-notification length is unaffected (hazard #7).
- [ ] **Step 3: Replace call sites**:
```kotlin
// service / builder (has context)
.setContentTitle(context.getString(R.string.notification_session_title))
```
For a pure copy-selector helper feeding both Compose and notification, return `@StringRes Int` and let each edge resolve.
- [ ] **Step 4: Verify + build** (Task 2 Step 4 on these paths).
- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/service app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize notification + recording-service copy"
```

---

### Task 10: Shared dialogs + `common_*`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/components/RovaDialogs.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/RovaCardComponents.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/components/RovaComponents.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/PreviewActivity.kt` (the `Toast` text `"No app available to share videos"` is user-facing → externalize; `intent.getStringExtra("VIDEO_PATH")` key is NON-user-facing → leave)
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Enumerate** (Grep each file). RovaDialogs.kt has ~9 `Text` + 1 `contentDescription` literals — buttons like Cancel/OK/Close are `common_*`.
- [ ] **Step 2: Add `common_*` resources** for shared affordances, dialog-specific ones under their domain:
```xml
<!-- Shared -->
<string name="common_cancel">Cancel</string>
<string name="common_ok">OK</string>
<string name="common_close">Close</string>
<string name="common_share_no_app">No app available to share videos</string>
```
Sweep earlier tasks' duplicate Cancel/OK/Close values into these `common_*` names if any slipped in (DRY).
- [ ] **Step 3: Replace call sites** (Compose → `stringResource`; `Toast.makeText(this, getString(R.string.common_share_no_app), …)` in the Activity).
- [ ] **Step 4: Verify + build** (Task 2 Step 4 on these files).
- [ ] **Step 5: Commit**
```powershell
git add app/src/main/java/com/aritr/rova/ui app/src/main/res/values/strings.xml
git commit -m "feat(i18n): externalize shared dialogs + common_* strings"
```

---

### Task 11: `checkNoHardcodedUiStrings` static gate

**Files:**
- Modify: `app/build.gradle.kts` (add task before the `afterEvaluate` block at ~line 1577; add `dependsOn` inside it after line 1604)

- [ ] **Step 1: Add the task**

Insert before `afterEvaluate {` (the existing block near line 1577):
```kotlin
// ADR-0022 §Invariant — user-facing strings must live in res/values.
// Regression tripwire (NOT a correctness proof): a regex scan over Compose +
// notification sources. Known holes it cannot see — string vars, wrapper
// composables, Toast/Snackbar/Dialog text, semantics{} blocks, templates — are
// documented in ADR-0022 and swept manually in the B3 slice. Opt-out: append
// `i18n-opt-out: <reason>` on a line (valid only for non-user-facing literals:
// protocol/key/debug/route).
val checkNoHardcodedUiStrings = tasks.register("checkNoHardcodedUiStrings") {
    group = "verification"
    description = "Forbid hardcoded user-facing string literals in Compose/notification sources (ADR-0022 §Invariant)."
    val uiDir = file("src/main/java/com/aritr/rova/ui")
    val notifDir = file("src/main/java/com/aritr/rova/service/notification")
    inputs.dir(uiDir).withPropertyName("uiSources")
    doLast {
        if (!uiDir.exists()) throw GradleException("UI source dir missing: $uiDir")
        val patterns = listOf(
            Regex("""\bText\s*\(\s*"([^"]*)""""),
            Regex("""\bcontentDescription\s*=\s*"([^"]*)""""),
            Regex("""\blabel\s*=\s*"([^"]*)"""")
        )
        val argOnly = Regex("""^%(\d+\$)?[sd]$|^%%$""")
        val offenders = mutableListOf<String>()
        listOf(uiDir, notifDir).filter { it.exists() }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.name.contains("Preview") }
                .forEach { f ->
                    f.readLines().forEachIndexed { idx, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        if (line.contains("i18n-opt-out:")) return@forEachIndexed
                        if (line.contains("RovaLog.") || line.contains("Log.")) return@forEachIndexed
                        if (line.contains("BuildConfig")) return@forEachIndexed
                        if (Regex("""throw\s+\w*Exception""").containsMatchIn(line)) return@forEachIndexed
                        if (trimmed.startsWith("@Preview")) return@forEachIndexed
                        for (p in patterns) {
                            val m = p.find(line) ?: continue
                            val literal = m.groupValues[1]
                            if (literal.isBlank()) continue
                            if (argOnly.matches(literal.trim())) continue
                            offenders += "  ${f.relativeTo(rootDir)}:${idx + 1}: ${line.trim()}"
                            break
                        }
                    }
                }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "User-facing string literals must live in res/values/strings.xml " +
                    "(ADR-0022 §Invariant). Use stringResource()/getString()/UiText. " +
                    "If a literal is genuinely non-user-facing (protocol/key/debug/route), " +
                    "append `i18n-opt-out: <reason>` on the line. Offenders:\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`**

Inside the `afterEvaluate { tasks.matching { it.name == "preBuild" }.configureEach { … } }` block, after the last existing `dependsOn(checkWakeLockZeroGapRefresh)` (line ~1604):
```kotlin
        dependsOn(checkNoHardcodedUiStrings)
```

- [ ] **Step 3: RED — prove the gate fails on a planted literal**

Temporarily add to any file under `ui/` (e.g. end of `RecordChrome.kt`, in a composable): `Text("PLANTED HARDCODED")`. Then:
```powershell
& ".\gradlew.bat" :app:checkNoHardcodedUiStrings
```
Expected: FAIL with `…RecordChrome.kt:NN: Text("PLANTED HARDCODED")` and the ADR-0022 message.

- [ ] **Step 4: GREEN — remove plant, prove the swept tree passes**

Remove the planted line. Then:
```powershell
& ".\gradlew.bat" :app:checkNoHardcodedUiStrings
```
Expected: `BUILD SUCCESSFUL` (no offenders — Tasks 2–10 swept everything; any intentional literal carries `i18n-opt-out:`).

If it reports offenders, they are real misses from earlier tasks: externalize them (or add `i18n-opt-out:` if genuinely non-user-facing) before continuing.

- [ ] **Step 5: Full gate + commit**
```powershell
& ".\gradlew.bat" :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`, 0 errors (runs every `check*` including the new one via `preBuild`).
```powershell
git add app/build.gradle.kts
git commit -m "feat(i18n): checkNoHardcodedUiStrings preBuild gate (ADR-0022)"
```

---

### Task 12: ADR-0022 + CHANGELOG

**Files:**
- Create: `app/../docs/adr/0022-user-facing-strings-in-resources.md` (repo path: `docs/adr/0022-user-facing-strings-in-resources.md`)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write ADR-0022**

```markdown
# ADR-0022 — User-facing strings live in resources

**Status:** Accepted

**Date:** 2026-06-01

**Relates to:** Track-B settings expansion (B3); precedes Phase B (localization).

## Context

Until B3, only ~64 onboarding/notification strings were in `res/values/strings.xml`;
150–250 user-facing strings were hardcoded Kotlin literals across `ui/` and
`service/`. The app could not be localized, and there was no guard against new
hardcoded copy.

## Decision

All user-facing strings live in `res/values/strings.xml` / `plurals.xml`,
referenced by stable domain-prefixed names (`record_*`, `history_*`, `settings_*`,
`player_*`, `warning_*`, `recovery_*`, `onboarding_*`, `notification_*`, `common_*`).

Resolution by scope:
- Compose: `stringResource` / `pluralStringResource` (count passed twice).
- Service / notification (has `Context`): `getString` / `getQuantityString`.
- Pure helper / ViewModel selecting fixed copy: return `@StringRes Int`.
- Pure helper / ViewModel owning args or plurals: return a `UiText` token
  (`ui/text/UiText.kt`), resolved at each edge.

ViewModels never store *resolved* strings in state — only IDs/tokens — so a future
Phase B locale switch refreshes naturally.

**Enforcement:** `checkNoHardcodedUiStrings` (a 26th `check*` task) scans `ui/` +
`service/notification/` for `Text("…")`, `contentDescription = "…"`, `label = "…"`
and fails `preBuild` citing this clause. Opt-out: `i18n-opt-out: <reason>` on a
line, valid **only** for non-user-facing literals (protocol/key/debug/route).

## Honest limitation

The gate is a **regression tripwire, not a correctness proof.** Regex cannot
understand Kotlin call structure; it does NOT catch: a literal assigned to a `val`
then passed; custom wrapper composables (`PrimaryButton("…")`); `Toast`/`Snackbar`/
`AlertDialog`/`DropdownMenuItem` text; `Modifier.semantics { contentDescription = … }`;
`buildAnnotatedString { append("…") }`; string templates. Those were swept manually
in B3. If the gate must become load-bearing, migrate to Android Lint UAST or a
Detekt PSI rule (Phase-B-or-later).

## Non-goals

No translation, no `android:localeConfig`, no locale picker — that is Phase B, a
later slice that builds on this externalization.

## Consequences

- The app is now externalized and ready to localize.
- One new `check*` gate; the 25 existing invariants are untouched.
- Zero runtime/behaviour change in B3 — pure resource indirection; English copy
  byte-identical.
```

- [ ] **Step 2: CHANGELOG entry**

Under `## [Unreleased]` (add the heading if absent), in an `### Added` / `### Changed` group:
```markdown
### Changed
- i18n Phase A: externalized all user-facing strings to `res/values` with a
  `checkNoHardcodedUiStrings` build gate (ADR-0022). No behaviour change;
  groundwork for localization (Phase B).
```

- [ ] **Step 3: Verify ADR cite resolves**

Confirm the gate's failure message and CLAUDE.md/ADR index reference "ADR-0022". No build step; this is a doc-consistency check.

- [ ] **Step 4: Commit**
```powershell
git add docs/adr/0022-user-facing-strings-in-resources.md CHANGELOG.md
git commit -m "docs(i18n): ADR-0022 user-facing-strings-in-resources + CHANGELOG"
```

---

## Final verification (after Task 12, before finishing the branch)

- [ ] Full gate green:
```powershell
& ".\gradlew.bat" :app:lintDebug
& ".\gradlew.bat" :app:testDebugUnitTest
& ".\gradlew.bat" :app:assembleDebug
```
Expected: all `BUILD SUCCESSFUL`; tests **1377/0-0-0** (unchanged — zero behaviour change); lint 0 errors; new `checkNoHardcodedUiStrings` passes inside `preBuild`.
- [ ] Tree-wide residual scan: `Grep pattern: Text\(\s*"|contentDescription\s*=\s*"|label\s*=\s*" path: app/src/main/java/com/aritr/rova` → only `i18n-opt-out:` lines remain.
- [ ] **Manual device smoke (mandatory):** install on the Samsung A17 (`adb install -r app/build/outputs/apk/debug/app-debug.apk`), open every screen — Record, History, Settings, Player, onboarding, warning sheets, recovery card, notifications — and confirm copy is **identical** to pre-slice (catches an escaping/format regression that compiles but renders wrong: stray `%`, lost apostrophe, double-spaced concatenation).
- [ ] Finish via superpowers:finishing-a-development-branch.

## Self-review (done by plan author)

- **Spec coverage:** UiText seam (T1) ✓; four buckets — visible Text (T2-6,10), contentDescription (T2,5,7,8), notification+service (T9), warning/recovery (T7,8) ✓; naming convention (all sweep tasks) ✓; gate (T11) ✓; ADR-0022 (T12) ✓; hazards (conventions header + T9) ✓; non-goals/opt-out (conventions + T8,10) ✓; tests assert IDs not English (T1) ✓; no resolved strings in VM state (T7,9) ✓.
- **Placeholder scan:** none — every code step shows real code; sweep tasks give the transform recipe + real enumerated literals + exact verification Grep/commands.
- **Type consistency:** `UiText.Str/StrArgs/Plural`, `resolve()`/`resolve(context)`, `checkNoHardcodedUiStrings`, resource-name prefixes used identically across all tasks.
