# i18n Phase B-i — Locale Infrastructure + Pseudolocale, Gated Picker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the runtime locale-switching machinery (native `LocaleManager` on API 33+, an `attachBaseContext` `Configuration` backport on API 24–32, no AppCompat) plus a gated in-app Language picker, validated by pseudolocales — with zero user-visible change this slice (picker hidden until a real reviewed catalog exists).

**Architecture:** Pure-helper `AppLocale` (BCP-47 tag logic, JVM-tested) + thin framework seam `LocaleApplier` (LocaleManager call + Configuration wrap). `RovaSettings.localeTag` is the single source of truth; `MainActivity.attachBaseContext` applies it pre-Compose on API 24–32; `LocaleManager` mirrors it on 33+. The Settings Language row renders only when `AppLocale.shouldShowLanguagePicker(...)` is true (false this slice). A 27th `check*` gate forbids pseudolocale tags leaking into the hand-authored `locales_config.xml`.

**Tech Stack:** Kotlin, Jetpack Compose, `android.app.LocaleManager` / `android.os.LocaleList` (API 33+), `Configuration.setLocale` + `createConfigurationContext` (24–32), AGP `isPseudoLocalesEnabled`, `res/xml/locales_config.xml`, `android:localeConfig`. JVM unit tests only (`isReturnDefaultValues = true`).

**Spec:** `docs/superpowers/specs/2026-06-02-i18n-phase-b-localization-design.md`
**Branch:** `feat/i18n-phase-b` (off clean master `495dda9`).

---

## File Structure

**Create:**
- `app/src/main/java/com/aritr/rova/ui/locale/AppLocale.kt` — pure helper: `LocaleOption`, `SUPPORTED_USER_LOCALES`, `localeTagFromStorage`, `languagePickerOptions`, `shouldShowLanguagePicker`, `localeFor`.
- `app/src/main/java/com/aritr/rova/ui/locale/LocaleApplier.kt` — thin framework seam: `apply(context, tag)`, `wrapContext(base, tag)`.
- `app/src/main/res/xml/locales_config.xml` — hand-authored `<locale-config>` (only `en`).
- `app/src/test/java/com/aritr/rova/ui/locale/AppLocaleTest.kt` — pure-helper tests.
- `docs/adr/0023-localization-locale-picker.md` — ADR-0023.

**Modify:**
- `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — add `localeTag: String?`.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — add `localeTag` flow + `setLocale`.
- `app/src/main/java/com/aritr/rova/MainActivity.kt` — override `attachBaseContext`.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — gated Language row + sheet.
- `app/src/main/res/values/strings.xml` — `settings_language_*` strings.
- `app/src/main/AndroidManifest.xml` — `android:localeConfig`.
- `app/build.gradle.kts` — debug `isPseudoLocalesEnabled`; `checkLocaleConfigNoPseudolocale` task + `preBuild` wire.
- `CHANGELOG.md` — entry.

**Testability note (house policy — CLAUDE.md):** JVM unit tests cover **pure helpers only** (`AppLocale`). Framework seams (`LocaleApplier`, `attachBaseContext`, `RovaSettings` SharedPreferences, the Compose row, the VM) are **not** JVM-testable under `isReturnDefaultValues = true` — exactly like the existing `themeMode` path, which also has no unit test. Those tasks verify by `:app:compileDebugKotlin` + the slice-exit device smoke. This is intentional, not a gap.

**Gradle note (this environment):** the controller runs Gradle through the PowerShell tool. Each verify command below is the canonical form; on a stalled/backgrounded run, do a clean `gradlew.bat --stop` then re-run the single targeted task.

---

### Task 1: `AppLocale` pure helper (TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/locale/AppLocale.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/locale/AppLocaleTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/locale/AppLocaleTest.kt`:

```kotlin
package com.aritr.rova.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLocaleTest {

    // Supported list is injected so these tests are stable when a real
    // language is later appended to AppLocale.SUPPORTED_USER_LOCALES.
    private val withEs = listOf("es")

    @Test fun `localeTagFromStorage coerces null blank and unknown to system`() {
        assertNull(AppLocale.localeTagFromStorage(null, withEs))
        assertNull(AppLocale.localeTagFromStorage("", withEs))
        assertNull(AppLocale.localeTagFromStorage("   ", withEs))
        assertNull(AppLocale.localeTagFromStorage("fr", withEs)) // not in supported
    }

    @Test fun `localeTagFromStorage returns a supported tag unchanged`() {
        assertEquals("es", AppLocale.localeTagFromStorage("es", withEs))
    }

    @Test fun `with no supported locales every stored tag coerces to system`() {
        assertNull(AppLocale.localeTagFromStorage("es", emptyList()))
        assertNull(AppLocale.localeTagFromStorage("en", emptyList()))
    }

    @Test fun `languagePickerOptions is system-only when nothing is supported`() {
        val opts = AppLocale.languagePickerOptions(emptyList())
        assertEquals(1, opts.size)
        assertNull(opts.single().tag) // the system-default sentinel
    }

    @Test fun `languagePickerOptions prepends system before each supported tag`() {
        val opts = AppLocale.languagePickerOptions(withEs)
        assertEquals(listOf(null, "es"), opts.map { it.tag })
    }

    @Test fun `shouldShowLanguagePicker is false when only system is offered`() {
        assertFalse(AppLocale.shouldShowLanguagePicker(AppLocale.languagePickerOptions(emptyList())))
    }

    @Test fun `shouldShowLanguagePicker is true once a real language exists`() {
        assertTrue(AppLocale.shouldShowLanguagePicker(AppLocale.languagePickerOptions(withEs)))
    }

    @Test fun `shipping default keeps the picker hidden`() {
        // Guards the "no empty promise" rule: with the shipped SUPPORTED list,
        // the Language row must not render this slice.
        assertFalse(AppLocale.shouldShowLanguagePicker(AppLocale.languagePickerOptions()))
    }

    @Test fun `localeFor maps a tag to a Locale and null to system`() {
        assertNull(AppLocale.localeFor(null))
        assertEquals(Locale.forLanguageTag("es"), AppLocale.localeFor("es"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.locale.AppLocaleTest"`
Expected: FAIL — `AppLocale` / `LocaleOption` unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/aritr/rova/ui/locale/AppLocale.kt`:

```kotlin
package com.aritr.rova.ui.locale

import java.util.Locale

/**
 * One picker entry. [tag] is a BCP-47 language tag, or `null` for the
 * "system default" sentinel (follow the OS locale).
 */
data class LocaleOption(val tag: String?)

/**
 * Pure, framework-free locale model for i18n Phase B (ADR-0023). Holds the
 * canonical list of *real, reviewed* in-app languages and the rules the
 * Settings picker and the persisted-tag coercion both depend on. Unit-testable
 * under `isReturnDefaultValues = true` (no Android types).
 *
 * Pseudolocales (`en_XA`, `ar_XB`) are a debug-only QA tool and are NEVER
 * members of [SUPPORTED_USER_LOCALES] — see ADR-0023.
 */
object AppLocale {

    /**
     * BCP-47 tags of catalogs that actually ship and have been reviewed.
     * Empty this slice → the in-app Language picker stays hidden (no empty
     * promise). A later slice appends one tag (e.g. "es") alongside its
     * `values-es/` catalog and a `<locale>` line in `locales_config.xml`.
     */
    val SUPPORTED_USER_LOCALES: List<String> = emptyList()

    /**
     * Coerce a persisted locale tag to a known-good value. `null`/blank/unknown
     * → `null` (system default), mirroring `themeModeFromStorage`'s defensive
     * coercion. `supported` is injectable for tests; defaults to the shipped list.
     */
    fun localeTagFromStorage(
        raw: String?,
        supported: List<String> = SUPPORTED_USER_LOCALES,
    ): String? {
        if (raw.isNullOrBlank()) return null
        return raw.takeIf { it in supported }
    }

    /** System-default sentinel first, then one entry per supported language. */
    fun languagePickerOptions(
        supported: List<String> = SUPPORTED_USER_LOCALES,
    ): List<LocaleOption> =
        listOf(LocaleOption(null)) + supported.map { LocaleOption(it) }

    /** True only when there is ≥1 real language beyond the system default. */
    fun shouldShowLanguagePicker(options: List<LocaleOption>): Boolean =
        options.any { it.tag != null }

    /** `null` tag → system (no override); otherwise the matching [Locale]. */
    fun localeFor(tag: String?): Locale? = tag?.let { Locale.forLanguageTag(it) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.locale.AppLocaleTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/locale/AppLocale.kt app/src/test/java/com/aritr/rova/ui/locale/AppLocaleTest.kt
git commit -m "feat(i18n): AppLocale pure helper for Phase B locale model (ADR-0023)"
```

---

### Task 2: `RovaSettings.localeTag` persistence

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` (add after the `themeMode` property, ~line 101)

No JVM test — SharedPreferences returns defaults under `isReturnDefaultValues`; the coercion logic is already covered by `AppLocaleTest`. Verify by compile (Task 5's test run also exercises the import).

- [ ] **Step 1: Add the property**

In `RovaSettings.kt`, add the import near the other `ui.theme` imports at the top:

```kotlin
import com.aritr.rova.ui.locale.AppLocale
```

Then add, immediately after the `themeMode` property (the block ending at line 101):

```kotlin
    // i18n Phase B (ADR-0023) — chosen app language as a BCP-47 tag; null =
    // follow system. Backed up (a genuine user preference, like themeMode).
    // Reads coerce through AppLocale.localeTagFromStorage so an unknown/stale
    // tag (incl. a language not yet shipped) falls back to system. On API 33+
    // LocaleManager is the live applier; this value mirrors it as the single
    // source of truth and feeds the API 24–32 attachBaseContext backport.
    var localeTag: String?
        get() = AppLocale.localeTagFromStorage(prefs.getString("locale_tag", null))
        set(value) = prefs.edit {
            if (value == null) remove("locale_tag") else putString("locale_tag", value)
        }
```

- [ ] **Step 2: Verify compile**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "feat(i18n): persist chosen locale tag in RovaSettings (ADR-0023)"
```

---

### Task 3: `LocaleApplier` framework seam

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/locale/LocaleApplier.kt`

No JVM test — pure tag→Locale logic lives in `AppLocale.localeFor` (already tested); the rest touches `LocaleManager` / `Configuration` and is exercised by the device smoke.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/aritr/rova/ui/locale/LocaleApplier.kt`:

```kotlin
package com.aritr.rova.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * Thin framework seam that applies a chosen locale (ADR-0023). Two API tiers,
 * no AppCompat:
 *
 *  - **API 33+** — [LocaleManager.setApplicationLocales]. The framework persists
 *    the per-app locale, recreates the Activity, and keeps the app in sync with
 *    the Android 13+ system per-app-language screen.
 *  - **API 24–32** — the locale is applied in [android.app.Activity.attachBaseContext]
 *    via [wrapContext]; [apply] just triggers [Activity.recreate] so the wrapped
 *    base context is re-attached from the freshly persisted tag.
 *
 * The persisted tag in `RovaSettings.localeTag` is the single source of truth;
 * callers MUST write it *before* calling [apply] so the 24–32 recreate path
 * reads the new value.
 */
object LocaleApplier {

    /** Apply [tag] (null = system default). Caller persists the tag first. */
    fun apply(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            manager.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
            // Framework recreates the Activity itself.
        } else {
            (context as? Activity)?.recreate()
        }
    }

    /**
     * API 24–32 only: wrap [base] with a [Configuration] pinned to the persisted
     * locale so Compose `stringResource` resolves the override. On API 33+ the
     * locale is already on [base] (LocaleManager applied it), so return it as-is.
     */
    fun wrapContext(base: Context, tag: String?): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = AppLocale.localeFor(tag) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/locale/LocaleApplier.kt
git commit -m "feat(i18n): LocaleApplier seam — LocaleManager (33+) + Configuration backport (24-32) (ADR-0023)"
```

---

### Task 4: `MainActivity.attachBaseContext` backport hook

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/MainActivity.kt`

No JVM test — Activity lifecycle seam; verified by compile + device smoke.

- [ ] **Step 1: Add the imports**

In `MainActivity.kt`, add near the existing imports:

```kotlin
import android.content.Context
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.ui.locale.LocaleApplier
```

- [ ] **Step 2: Override attachBaseContext**

Add this override inside `class MainActivity`, immediately above `onCreate` (after the class brace at line 20):

```kotlin
    // i18n Phase B (ADR-0023) — API 24–32 locale backport. On those API levels
    // there is no LocaleManager, so the persisted tag is applied here, before
    // the Compose tree resolves resources. No-op on API 33+ (LocaleManager
    // already wrapped newBase) and when the tag is null (system default).
    override fun attachBaseContext(newBase: Context) {
        val tag = RovaSettings(newBase).localeTag
        super.attachBaseContext(LocaleApplier.wrapContext(newBase, tag))
    }
```

- [ ] **Step 3: Verify compile**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/MainActivity.kt
git commit -m "feat(i18n): apply persisted locale in MainActivity.attachBaseContext for API 24-32 (ADR-0023)"
```

---

### Task 5: `SettingsViewModel` locale flow + `setLocale`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt`

No JVM test — `AndroidViewModel` framework seam (the existing VM has none); decision logic is trivial and the testable coercion is in `AppLocale`.

- [ ] **Step 1: Add the flow + setter**

In `SettingsViewModel.kt`, add the import:

```kotlin
import com.aritr.rova.ui.locale.LocaleApplier
```

Add the flow after the `themeMode` flow (line 50):

```kotlin
    // i18n Phase B (ADR-0023) — chosen language tag, null = system. UNLIKE
    // themeMode (which write-backs via a collector), localeTag is persisted
    // SYNCHRONOUSLY in setLocale(): the API 24–32 recreate path reads the value
    // in attachBaseContext, so it must be on disk before apply() recreates.
    val localeTag = MutableStateFlow(settings.localeTag)
```

Add this method to the class body (e.g. after `reloadRecordingDefaults`, before the closing brace at line 82):

```kotlin
    /**
     * Set the app language. Persists the tag first (the API 24–32 backport reads
     * it in attachBaseContext on recreate), updates the flow, then applies:
     * LocaleManager on API 33+ (framework recreates) or Activity.recreate()
     * on 24–32. [context] must be the Activity.
     */
    fun setLocale(context: Context, tag: String?) {
        settings.localeTag = tag
        localeTag.value = tag
        LocaleApplier.apply(context, tag)
    }
```

(`Context` is already imported at line 4.)

- [ ] **Step 2: Verify compile**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt
git commit -m "feat(i18n): SettingsViewModel localeTag flow + setLocale (ADR-0023)"
```

---

### Task 6: Settings Language row + picker sheet (gated) + strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

No JVM test — Compose UI. The row is gated hidden this slice; verified by compile, the `checkNoHardcodedUiStrings` gate staying green, and device smoke.

- [ ] **Step 1: Add the strings**

In `app/src/main/res/values/strings.xml`, add next to the existing `settings_theme_*` entries:

```xml
    <string name="settings_language_label">Language</string>
    <string name="settings_language_supporting">Choose the app language</string>
    <string name="settings_language_system">System default</string>
```

- [ ] **Step 2: Add imports + state in SettingsScreen**

In `SettingsScreen.kt` add the import:

```kotlin
import com.aritr.rova.ui.locale.AppLocale
```

Add state next to `openThemeSheet` (line 163):

```kotlin
    var openLanguageSheet by remember { mutableStateOf(false) }
    val languageOptions = AppLocale.languagePickerOptions()
    val showLanguageRow = AppLocale.shouldShowLanguagePicker(languageOptions)
    val localeTag by settingsViewModel.localeTag.collectAsStateWithLifecycle()
```

- [ ] **Step 3: Add the gated row in the Appearance section**

In the `SettingsSection(... settings_section_appearance ...)` block (after the theme `SettingsRow`, line 209), add:

```kotlin
                if (showLanguageRow) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.settings_language_label),
                        supporting = stringResource(R.string.settings_language_supporting),
                        value = languageOptionLabel(localeTag),
                        onClick = { openLanguageSheet = true },
                        trailing = { ChevronTrailing() },
                    )
                }
```

Add the icon import with the other `Icons.Default.*` imports:

```kotlin
import androidx.compose.material.icons.filled.Language
```

- [ ] **Step 4: Add the picker sheet**

After the `if (openThemeSheet) { ... }` block (closes at line 523), add:

```kotlin
    if (openLanguageSheet) {
        val systemLabel = stringResource(R.string.settings_language_system)
        SettingsOptionSheet(
            title = stringResource(R.string.settings_language_label),
            options = languageOptions,
            selected = languageOptions.firstOrNull { it.tag == localeTag } ?: languageOptions.first(),
            optionLabel = { option ->
                if (option.tag == null) systemLabel else endonymOf(option.tag)
            },
            onPick = { settingsViewModel.setLocale(context, it.tag) },
            onDismiss = { openLanguageSheet = false },
        )
    }
```

(`context` is `LocalContext.current` at line 120 — the Activity.)

- [ ] **Step 5: Add the label helpers**

Next to `themeModeLabel` (line 805), add:

```kotlin
@Composable
private fun languageOptionLabel(tag: String?): String =
    if (tag == null) stringResource(R.string.settings_language_system) else endonymOf(tag)

/** Endonym (language name in its own language), capitalised. */
private fun endonymOf(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
}
```

- [ ] **Step 6: Verify compile + gate green**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `gradlew.bat :app:checkNoHardcodedUiStrings`
Expected: BUILD SUCCESSFUL (all new copy uses `stringResource` / computed endonym, none hardcoded).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(i18n): gated Language picker row + sheet in Settings (hidden until a real locale ships) (ADR-0023)"
```

---

### Task 7: `locales_config.xml` + manifest `localeConfig`

**Files:**
- Create: `app/src/main/res/xml/locales_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (the `<application>` tag, line 36–46)

- [ ] **Step 1: Create the hand-authored locale config**

Create `app/src/main/res/xml/locales_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  i18n Phase B (ADR-0023). HAND-AUTHORED — AGP generateLocaleConfig is left OFF
  so debug pseudolocale folders (values-en-rXA / values-ar-rXB) are never
  published into the Android 13+ system per-app-language list. Lists only real
  shippable locales; each future language adds one <locale> line here alongside
  its values-xx/ catalog and AppLocale.SUPPORTED_USER_LOCALES entry.
  The checkLocaleConfigNoPseudolocale gate forbids pseudolocale tags here.
-->
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
</locale-config>
```

- [ ] **Step 2: Reference it from the manifest**

In `AndroidManifest.xml`, add `android:localeConfig="@xml/locales_config"` to the `<application>` element (insert after `android:label="@string/app_name"`, line 42):

```xml
        android:label="@string/app_name"
        android:localeConfig="@xml/locales_config"
        android:roundIcon="@mipmap/ic_launcher_round"
```

- [ ] **Step 3: Verify lint/merge**

Run: `gradlew.bat :app:processDebugManifest`
Expected: BUILD SUCCESSFUL (manifest merges; `localeConfig` resolves).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/locales_config.xml app/src/main/AndroidManifest.xml
git commit -m "feat(i18n): hand-authored locales_config.xml + android:localeConfig (ADR-0023)"
```

---

### Task 8: Enable pseudolocales for the debug build

**Files:**
- Modify: `app/build.gradle.kts` (the `buildTypes { ... }` block, line 48–58)

- [ ] **Step 1: Add the debug build-type config**

In `app/build.gradle.kts`, inside `buildTypes { ... }`, add a `debug` block above `release`:

```kotlin
    buildTypes {
        debug {
            // i18n Phase B (ADR-0023) — bake en_XA (accent + ~30–40% text
            // expansion + [bracket bounds]) and ar_XB (RTL bidi mirror) into the
            // DEBUG apk only, as the localizability QA harness. Release is
            // untouched; pseudolocales are never offered in the in-app picker.
            isPseudoLocalesEnabled = true
        }
        release {
```

(Leave the existing `release { ... }` body unchanged.)

- [ ] **Step 2: Verify the debug build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(i18n): enable pseudolocales (en_XA/ar_XB) for debug build (ADR-0023)"
```

---

### Task 9: `checkLocaleConfigNoPseudolocale` static gate (RED→GREEN)

**Files:**
- Modify: `app/build.gradle.kts` (add the task after `checkNoHardcodedUiStrings` at line 1647; wire into `preBuild` at line 1677)

- [ ] **Step 1: Add the check task**

In `app/build.gradle.kts`, immediately after the `checkNoHardcodedUiStrings` task's closing brace (line 1647, before `afterEvaluate {`), add:

```kotlin
val checkLocaleConfigNoPseudolocale = tasks.register("checkLocaleConfigNoPseudolocale") {
    group = "verification"
    description = "Forbid pseudolocale tags (en-XA/ar-XB) in res/xml/locales_config.xml — they must never reach the system per-app-language list (ADR-0023 §No Pseudolocale In LocaleConfig)."
    val configFile = file("src/main/res/xml/locales_config.xml")
    inputs.file(configFile).withPropertyName("localesConfig")
    doLast {
        if (!configFile.exists()) {
            throw GradleException("checkLocaleConfigNoPseudolocale: locales_config.xml missing: $configFile")
        }
        // Pseudolocale tags in any android resource form: en-XA / en-rXA / ar-XB / ar-rXB.
        val pseudo = Regex("""\b(en|ar)-r?X[AB]\b""", RegexOption.IGNORE_CASE)
        val offenders = configFile.readLines()
            .withIndex()
            .filter { (_, line) -> pseudo.containsMatchIn(line) }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (i, line) ->
                "  ${configFile.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
            }
            throw GradleException(
                "Pseudolocale tag(s) found in locales_config.xml. Pseudolocales " +
                    "(en_XA / ar_XB) are a DEBUG-ONLY QA tool and must never be " +
                    "advertised as a user language (ADR-0023 §No Pseudolocale In " +
                    "LocaleConfig). Remove them; keep generateLocaleConfig OFF. " +
                    "Offenders:\n$report"
            )
        }
    }
}
```

- [ ] **Step 2: Wire it into preBuild**

In the `afterEvaluate { tasks.matching { it.name == "preBuild" } ... }` block, add after `dependsOn(checkNoHardcodedUiStrings)` (line 1677):

```kotlin
        dependsOn(checkNoHardcodedUiStrings)
        dependsOn(checkLocaleConfigNoPseudolocale)
```

- [ ] **Step 3: Prove GREEN on the real file**

Run: `gradlew.bat :app:checkLocaleConfigNoPseudolocale`
Expected: BUILD SUCCESSFUL (the hand-authored config lists only `en`).

- [ ] **Step 4: Prove RED (tripwire works)**

Temporarily add a pseudolocale line to `app/src/main/res/xml/locales_config.xml`:

```xml
    <locale android:name="ar-XB" />
```

Run: `gradlew.bat :app:checkLocaleConfigNoPseudolocale`
Expected: FAIL — `GradleException` citing `ADR-0023 §No Pseudolocale In LocaleConfig`, listing `locales_config.xml:<line>: <locale android:name="ar-XB" />`.

Then **remove** the temporary line and re-run:

Run: `gradlew.bat :app:checkLocaleConfigNoPseudolocale`
Expected: BUILD SUCCESSFUL again.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(i18n): checkLocaleConfigNoPseudolocale — 27th preBuild gate (ADR-0023)"
```

---

### Task 10: ADR-0023 + CHANGELOG

**Files:**
- Create: `docs/adr/0023-localization-locale-picker.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write ADR-0023**

Create `docs/adr/0023-localization-locale-picker.md`:

```markdown
# ADR-0023 — Localization: locale-apply mechanism + gated in-app Language picker (i18n Phase B-i)

**Status:** Accepted

**Date:** 2026-06-02

**Deciders:** Rova owner

**Supersedes / amends:** none. Builds on ADR-0022 (Phase A externalization) — only the already-externalized strings are localizable, so Phase B is a resource-and-picker add with no call-site churn.

## Context

Phase A (ADR-0022) moved all ~470 user-facing strings into `res/values/`. Phase B is localization. This first slice (B-i) builds the locale-switching machinery and a gated in-app Language picker, validated by pseudolocales, with **no real translated catalog and no user-visible change**. The picker stays hidden until a real, reviewed language exists.

End-user UX rationale: a "Language" setting that lists only English is an empty promise; unreviewed machine-translated copy in a privacy-sensitive recording app is worse (wrong permission/deletion/recovery copy erodes trust). So this slice ships no false promise — infra + pseudolocale QA only; each real language lands later as its own reviewed `values-xx/` drop-in.

## Decision

### No AppCompat

The app is deliberately `ComponentActivity` + Compose, edge-to-edge (ADR-0011) with route-aware system bars (B2). Converting to `AppCompatActivity` for `AppCompatDelegate.setApplicationLocales` would risk regressing both for no current benefit. The locale backport is hand-rolled instead.

### Locale-apply mechanism (two tiers)

- **API 33+:** `LocaleManager.setApplicationLocales(LocaleList)`. The framework persists the per-app locale, recreates the Activity, and syncs with the Android 13+ system per-app-language screen.
- **API 24–32:** `MainActivity.attachBaseContext` wraps the base `Context` with a `Configuration.setLocale(...)` built from the persisted tag, so Compose `stringResource` resolves the override; a picker change persists the tag then calls `Activity.recreate()`.

`RovaSettings.localeTag` (BCP-47 tag, null = system) is the single source of truth; it is persisted **synchronously before** apply so the 24–32 recreate path reads it. Pure logic (tag coercion, picker options, tag→Locale) lives in `AppLocale`; the framework calls live in the thin `LocaleApplier` seam.

### Picker gating — no empty promise

The Settings Language row renders only when `AppLocale.shouldShowLanguagePicker(...)` is true, i.e. `SUPPORTED_USER_LOCALES` is non-empty. It is `emptyList()` this slice → row hidden. The first real-language slice appends one tag (+ `values-xx/` catalog + one `<locale>` line in `locales_config.xml`) and the row appears.

### locales_config + pseudolocales

`res/xml/locales_config.xml` is **hand-authored** (only `en` now) and referenced via `android:localeConfig`. AGP `generateLocaleConfig` is left **off** so debug pseudolocale folders never leak into the system language list. Pseudolocales (`en_XA`, `ar_XB`) are enabled for the **debug build only** (`isPseudoLocalesEnabled = true`) as the localizability QA harness; they are never in `SUPPORTED_USER_LOCALES` or `locales_config.xml`.

### §No Pseudolocale In LocaleConfig — `checkLocaleConfigNoPseudolocale`

The 27th `check*` gate (wired into `preBuild`). Scans `res/xml/locales_config.xml`; fails with a `GradleException` citing this clause if it finds a pseudolocale tag (`en-XA`/`ar-XB`, incl. the `-rXA` resource form, case-insensitive). A line-oriented tripwire consistent with the existing gates, not a correctness proof.

## Non-goals (Phase B-i)

No real translated catalog; no picker reveal; no full RTL pre-audit (only what pseudolocales surface during this slice's device smoke); no professional-translation pipeline. Each real language is a later, review-gated slice.

## Consequences

- A future language = pure resource add: `values-xx/` + one `SUPPORTED_USER_LOCALES` entry + one `locales_config.xml` line + review. No Kotlin churn.
- The hand-authored localeConfig + the new gate lock the pseudolocale-leak shut.
- Cost: the locale switch (unlike the theme switch) requires an Activity recreate, by nature of resource resolution — documented so it is not "fixed" to mirror theme.

## Hard invariants preserved

- No existing `check*` gate edited; this adds the 27th. Phase A's `checkNoHardcodedUiStrings` (26th) is untouched.
- No AppCompat dependency; `MainActivity` stays `ComponentActivity`.
- ViewModels/helpers store only a `String?` tag, never resolved copy (consistent with ADR-0022 §No Hardcoded UI Strings).
- House conventions: pure-helper extraction (`AppLocale`), thin framework seam (`LocaleApplier`, `attachBaseContext`), ADR-clause → `check*` → `preBuild`.

## Implementation reference

`docs/superpowers/specs/2026-06-02-i18n-phase-b-localization-design.md` and `docs/superpowers/plans/2026-06-02-i18n-phase-b-localization.md`.
```

- [ ] **Step 2: Add the CHANGELOG entry**

In `CHANGELOG.md`, under the `## [Unreleased]` → `### Added` section (create the heading if absent), add:

```markdown
- i18n Phase B-i: locale-switching infrastructure (LocaleManager on API 33+, `attachBaseContext` `Configuration` backport on 24–32, no AppCompat) + a gated in-app Language picker, validated by pseudolocales. No translated catalogs yet; the picker stays hidden until a real reviewed locale ships. Adds ADR-0023 and the 27th static gate (`checkLocaleConfigNoPseudolocale`).
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0023-localization-locale-picker.md CHANGELOG.md
git commit -m "docs(i18n): ADR-0023 localization mechanism + CHANGELOG (Phase B-i)"
```

---

### Final verification gate (slice exit)

- [ ] **Compile + unit tests**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: baseline (1381) + 10 new `AppLocaleTest` cases, 0 failures / 0 errors.

- [ ] **Static-check gate (27 checks via preBuild)**

Run: `gradlew.bat :app:lintDebug`
Expected: BUILD SUCCESSFUL; `checkNoHardcodedUiStrings` and `checkLocaleConfigNoPseudolocale` both green.

- [ ] **Debug APK with pseudolocales**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Device smoke (owner)** — install the debug APK and confirm:
  1. **No visible change in normal use** — the Language row is absent (picker gated off).
  2. **`en_XA`** (Developer options → enable, or set system language to "English (XA)"): copy shows accented/expanded text with bracket bounds across all 12 surfaces; note any clipping/truncation and fix the offending fixed width/`maxLines`.
  3. **`ar_XB`**: layout mirrors RTL with no broken `start`/`end`; fix any hardcoded left/right surfaced.
  Fixes are scoped to what the pseudolocales surface — not a full pre-audit.

---

## Self-Review

**Spec coverage:** scope (infra + pseudolocale, gated picker) → Tasks 1–9; LocaleManager/backport mechanism → Tasks 3–5; persistence single-source-of-truth → Task 2; picker gating → Tasks 1+6; hand-authored localeConfig + generateLocaleConfig-off → Task 7; pseudolocale debug-only → Task 8; ADR-0023 + 27th gate → Tasks 9–10; testing (pure helpers) → Task 1; device-smoke RTL/expansion scope → final gate. All spec sections mapped.

**Placeholder scan:** none — every code step shows complete code; every run step shows the exact command + expected result.

**Type consistency:** `AppLocale.SUPPORTED_USER_LOCALES`, `LocaleOption(tag)`, `localeTagFromStorage(raw, supported)`, `languagePickerOptions(supported)`, `shouldShowLanguagePicker(options)`, `localeFor(tag)`, `LocaleApplier.apply(context, tag)` / `wrapContext(base, tag)`, `RovaSettings.localeTag`, `SettingsViewModel.localeTag` / `setLocale(context, tag)` — names and signatures match across Tasks 1–6 and the ADR.
