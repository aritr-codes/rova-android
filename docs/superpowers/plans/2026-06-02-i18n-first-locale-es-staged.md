# i18n First Real Locale (Spanish, staged) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a full Spanish (`es`) catalog as a *staged* locale — reachable via Android's system per-app-language list (API 33+) — while the in-app Language picker stays hidden pending native review.

**Architecture:** Pure resource-and-config add. No Kotlin production-code change. `values-es/strings.xml` + `values-es/plurals.xml` translate the existing English catalog 1:1; `locales_config.xml` gains one `<locale>` line; `AppLocale.SUPPORTED_USER_LOCALES` stays `emptyList()` so the in-app row stays hidden. A pure-JVM `SpanishCatalogParityTest` plus Android lint (`MissingTranslation`/`ExtraTranslation`) guard structural integrity; native review (deferred) guards polish.

**Tech Stack:** Android resources (`values-es/`), `locales_config.xml`, JUnit4 pure-JVM test (javax.xml DOM parsing — no Android types), AGP lint, Gradle `check*` gate suite.

**Spec:** `docs/superpowers/specs/2026-06-02-i18n-first-locale-es-staged-design.md`

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `app/src/test/java/com/aritr/rova/ui/locale/SpanishCatalogParityTest.kt` | Pure-JVM structural guard: name-set parity, format-arg multiset parity, plural-category parity between `values/` and `values-es/`. | Create |
| `app/src/main/res/values-es/strings.xml` | Spanish translations of all 472 `<string>` entries, placeholders preserved. | Create |
| `app/src/main/res/values-es/plurals.xml` | Spanish translations of all 10 `<plurals>`, `one`/`other`, placeholders preserved. | Create |
| `app/src/main/res/xml/locales_config.xml` | Add `<locale android:name="es" />` so the system per-app-language list (33+) offers Spanish. | Modify |
| `docs/adr/0023-localization-locale-picker.md` | Add `§Staged Locale Before Reveal`; correct the §Picker-gating "first real-language slice" sentence to decouple catalog/locales_config from `SUPPORTED_USER_LOCALES`. | Modify |
| `CHANGELOG.md` | One bullet under `[Unreleased] ▸ Added`. | Modify |

**Untouched (must stay so):** `AppLocale.kt`, `LocaleApplier.kt`, `RovaSettings.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `MainActivity.kt`, all 27 `check*` tasks.

---

## Translation reference (used by Tasks 2 & 3)

This is the one artifact the plan cannot pre-write line-for-line — the deliverable *is* natural-language translation. The implementer translates every key in the English source, governed by the rules below, with the parity test + lint as the **structural** acceptance gate and the deferred native review as the **polish** gate.

### Register

- **Actions / buttons / labels →** neutral infinitive: Record→**Grabar**, Stop→**Detener**, Pause→**Pausar**, Resume→**Reanudar**, Export→**Exportar**, Share→**Compartir**, Delete→**Eliminar**, Cancel→**Cancelar**, Save→**Guardar**, Settings→**Ajustes**, Retry→**Reintentar**, Review→**Revisar**, Dismiss→**Descartar**, Allow→**Permitir**, Open→**Abrir**.
- **Direct address →** informal **tú** ("Permite que Rova grabe…", "Tu grabación…"), never *usted*.
- **Untranslated:** brand **Rova**; feature name **DualShot**; **Bitrate**; **mAh**; units already symbolic (MB, GB, FPS); proper nouns.

### Glossary (keep consistent across ALL keys)

| English | Spanish |
|---|---|
| recording (the act) | grabación |
| recording (a saved file) | grabación |
| clip | clip |
| segment | segmento |
| Library | Biblioteca |
| History | Historial |
| storage | almacenamiento |
| permission | permiso |
| camera | cámara |
| microphone | micrófono |
| notification | notificación |
| battery | batería |
| background | segundo plano |
| foreground | primer plano |
| warning | advertencia |
| onboarding (verb sense) | introducción / configuración inicial |
| portrait | vertical |
| landscape | horizontal |
| quality | calidad |
| duration | duración |
| interrupted | interrumpida/o |
| recovered | recuperado/a |
| merge (videos) | combinar |
| schedule | programar |

### Placeholder & escaping rules (NON-NEGOTIABLE — lint + parity test enforce)

- Preserve **every** format arg exactly: `%1$s`, `%2$d`, `%d`, `%s`, `%1$d`. Same set and per-index count as the English key. **Positional args (`%n$…`) MAY be reordered** to fit Spanish word order (e.g. `%1$s of %2$s` → `%2$s de %1$s`); non-positional (`%s`,`%d`) must NOT be reordered.
- Literal percent stays `%%`. Preserve `\n`, `\t`, any `xliff:g`, CDATA, and embedded markup verbatim.
- Android XML escaping: apostrophe `'`→`\'`, double-quote `"`→`\"`, `&`→`&amp;`, `<`→`&lt;`. A string starting with `@` or `?` → escape the leading char (`\@`). Spanish `¿ ¡ á é í ó ú ñ ü` are plain UTF-8 — do **not** escape them.
- Copy `translatable="false"` entries are **omitted** from `values-es/` (currently 0 such entries; if any appear, skip them — translating them is an `ExtraTranslation` error).

### Worked examples

```xml
<!-- English: <string name="record_start">Start recording</string> -->
<string name="record_start">Iniciar grabación</string>

<!-- English: <string name="export_saved_to">Saved %1$s to %2$s</string>  (positional reorder OK) -->
<string name="export_saved_to">%1$s guardado en %2$s</string>

<!-- English apostrophe: <string name="perm_rationale">Rova can't record without the camera</string> -->
<string name="perm_rationale">Rova no puede grabar sin la cámara</string>

<!-- English with escaped apostrophe stays escaped if Spanish needs one: -->
<string name="dont_lose">No pierdas tu grabación</string>  <!-- no apostrophe needed here -->

<!-- Plural worked example (English record_recovery_chip): -->
<plurals name="record_recovery_chip">
    <item quantity="one">%1$d grabación interrumpida · Revisar</item>
    <item quantity="other">%1$d grabaciones interrumpidas · Revisar</item>
</plurals>
```

### Known tight-fit strings to flag for device spot-check (Spanish ~20–30% longer)

Single-line HUD pills, segmented mode-tab labels (Portrait/Landscape/P+L), Start/Stop FAB labels, and fixed-width chips. The implementer lists in their report any translated string that is >30% longer than its English source so device review can target them. (This is a report item, not a build gate.)

---

## Task 1: SpanishCatalogParityTest (structural guard, TDD-red first)

**Files:**
- Create: `app/src/test/java/com/aritr/rova/ui/locale/SpanishCatalogParityTest.kt`

Locating resources: Gradle runs unit tests with the **module dir** (`app/`) as the working directory. The test resolves `src/main/res/...`, falling back to `app/src/main/res/...` for IDE runners whose CWD is the repo root.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-JVM structural parity guard for the staged Spanish catalog (ADR-0023
 * §Staged Locale Before Reveal). Verifies STRUCTURE only — every English key is
 * translated, no extra keys, format-arg multiset preserved, plural categories
 * present. Translation *quality/polish* is the deferred native-review gate, not
 * this test. Defense-in-depth behind lint MissingTranslation/ExtraTranslation.
 */
class SpanishCatalogParityTest {

    private fun resFile(rel: String): File {
        val a = File("src/main/res/$rel")
        if (a.exists()) return a
        val b = File("app/src/main/res/$rel")
        check(b.exists()) { "Resource not found: $rel (cwd=${File(".").absolutePath})" }
        return b
    }

    private fun parse(rel: String): Element {
        val f = resFile(rel)
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(f)
        return doc.documentElement
    }

    /** All `%n$x`, `%x` format specifiers in a string, as an order-independent multiset. */
    private fun formatArgs(text: String): Map<String, Int> {
        // %% is a literal percent — strip before scanning.
        val cleaned = text.replace("%%", "")
        val rx = Regex("%(\\d+\\$)?[a-zA-Z]")
        return rx.findAll(cleaned).map { it.value }.groupingBy { it }.eachCount()
    }

    private fun stringEntries(rootRel: String): Map<String, String> {
        val root = parse(rootRel)
        val nodes = root.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("translatable") == "false") continue
            out[el.getAttribute("name")] = el.textContent
        }
        return out
    }

    @Test
    fun stringNameSetsMatch() {
        val en = stringEntries("values/strings.xml").keys
        val es = stringEntries("values-es/strings.xml").keys
        val missing = en - es
        val extra = es - en
        assertTrue("Missing Spanish translations: $missing", missing.isEmpty())
        assertTrue("Extra Spanish keys not in English: $extra", extra.isEmpty())
    }

    @Test
    fun stringFormatArgsMatchPerKey() {
        val en = stringEntries("values/strings.xml")
        val es = stringEntries("values-es/strings.xml")
        val mismatches = en.keys.filter { k ->
            es.containsKey(k) && formatArgs(en.getValue(k)) != formatArgs(es.getValue(k))
        }.associateWith { k -> "en=${formatArgs(en.getValue(k))} es=${formatArgs(es.getValue(k))}" }
        assertEquals("Format-arg multiset drift: $mismatches", emptyMap<String, String>(), mismatches)
    }

    @Test
    fun pluralCatalogsMatch() {
        val en = parse("values/plurals.xml").getElementsByTagName("plurals")
        val es = parse("values-es/plurals.xml").getElementsByTagName("plurals")
        fun names(n: org.w3c.dom.NodeList) = (0 until n.length)
            .map { (n.item(it) as Element).getAttribute("name") }.toSet()
        assertEquals("Plural name sets differ", names(en), names(es))
    }

    @Test
    fun spanishPluralsHaveOneAndOther() {
        val es = parse("values-es/plurals.xml").getElementsByTagName("plurals")
        val bad = mutableListOf<String>()
        for (i in 0 until es.length) {
            val p = es.item(i) as Element
            val items = p.getElementsByTagName("item")
            val qty = (0 until items.length)
                .map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
            if (!qty.containsAll(setOf("one", "other"))) bad += p.getAttribute("name")
        }
        assertTrue("Spanish plurals missing one/other: $bad", bad.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (RED)**

Run (PowerShell, from repo root):
```powershell
Set-Location "g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android"
& .\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.locale.SpanishCatalogParityTest" --no-daemon
```
Expected: FAIL — `IllegalStateException: Resource not found: values-es/strings.xml` (the catalog does not exist yet). This proves the test actually reaches the Spanish files.

- [ ] **Step 3: Commit the RED test**

```powershell
git add app/src/test/java/com/aritr/rova/ui/locale/SpanishCatalogParityTest.kt
git commit -m @'
test(i18n): SpanishCatalogParityTest — structural guard (RED until values-es lands)

Name-set + format-arg multiset + plural-category parity between values/ and
values-es/. Pure-JVM DOM parse, positional args compared order-independent
(multiset) so legitimate Spanish reordering passes. ADR-0023 staged-locale.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
'@
```

---

## Task 2: values-es/strings.xml (full Spanish translation)

**Files:**
- Create: `app/src/main/res/values-es/strings.xml`

Translate **every** non-`translatable="false"` `<string>` in `app/src/main/res/values/strings.xml` per the **Translation reference** above. Keep the same `name` for each; keep the file's `<resources>` wrapper; do not add/remove keys.

- [ ] **Step 1: Read the English source in full**

```powershell
Get-Content "app\src\main\res\values\strings.xml"
```
Note every `name`, every format arg, every escaped char.

- [ ] **Step 2: Create `values-es/strings.xml` with all 472 translated entries**

Structure (translate every entry — abbreviated here; produce the full set):
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Rova</string>
    <string name="record_start">Iniciar grabación</string>
    <!-- … all remaining keys from values/strings.xml, translated per the glossary … -->
</resources>
```
Rules recap: preserve placeholders (multiset; positional reorder allowed), escape `'` `"` `&` `<`, omit `translatable="false"` keys, keep Rova/DualShot/Bitrate untranslated.

- [ ] **Step 3: Run the string-parity tests (expect strings GREEN, plurals still RED)**

```powershell
& .\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.locale.SpanishCatalogParityTest" --no-daemon
```
Expected: `stringNameSetsMatch` and `stringFormatArgsMatchPerKey` **PASS**; `pluralCatalogsMatch`/`spanishPluralsHaveOneAndOther` still **FAIL** (no `values-es/plurals.xml` yet → `Resource not found`). If a string test fails, fix the offending key (missing/extra name, or placeholder drift) before continuing.

- [ ] **Step 4: Run lint MissingTranslation/ExtraTranslation over the new catalog**

```powershell
& .\gradlew.bat :app:lintDebug --no-daemon
```
Expected: BUILD SUCCESSFUL. No `MissingTranslation` (would mean a key wasn't translated) and no `ExtraTranslation` (a key not in English). If lint reports either, reconcile against `values/strings.xml`.

- [ ] **Step 5: Report tight-fit strings, then commit**

In your report, list any Spanish string >30% longer than its English source (device spot-check targets). Then:
```powershell
git add app/src/main/res/values-es/strings.xml
git commit -m @'
feat(i18n): Spanish (es) strings catalog — 472 entries, staged

Full translation of values/strings.xml. Placeholders preserved (multiset),
neutral-infinitive register + informal tú, Rova/DualShot/Bitrate untranslated.
Reachable via system per-app-language list (33+); in-app picker still gated.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
'@
```

---

## Task 3: values-es/plurals.xml (Spanish plurals)

**Files:**
- Create: `app/src/main/res/values-es/plurals.xml`

Translate all 10 `<plurals>` from `app/src/main/res/values/plurals.xml`. Spanish uses the same two categories as English (`one`, `other`). Preserve each format arg exactly (several pass `%1$d` for both rule-selection and fill; `player_top_sub` and `notification_complete_body_dur` also carry `%2$s`).

- [ ] **Step 1: Create `values-es/plurals.xml` with all 10 plurals**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <plurals name="record_recovery_chip">
        <item quantity="one">%1$d grabación interrumpida · Revisar</item>
        <item quantity="other">%1$d grabaciones interrumpidas · Revisar</item>
    </plurals>
    <plurals name="record_recovery_chip_cd">
        <item quantity="one">%1$d grabación interrumpida. Revisar.</item>
        <item quantity="other">%1$d grabaciones interrumpidas. Revisar.</item>
    </plurals>
    <plurals name="history_selected_count">
        <item quantity="one">%d seleccionado</item>
        <item quantity="other">%d seleccionados</item>
    </plurals>
    <plurals name="history_delete_failed_count">
        <item quantity="one">No se pudo eliminar %d grabación</item>
        <item quantity="other">No se pudieron eliminar %d grabaciones</item>
    </plurals>
    <plurals name="player_top_sub">
        <item quantity="one">%1$d clip · %2$s en total</item>
        <item quantity="other">%1$d clips · %2$s en total</item>
    </plurals>
    <plurals name="settings_warnings_hidden_count">
        <item quantity="one">%d advertencia oculta</item>
        <item quantity="other">%d advertencias ocultas</item>
    </plurals>
    <plurals name="recovery_progress_cd_recovered">
        <item quantity="one">%1$d clip recuperado.</item>
        <item quantity="other">%1$d clips recuperados.</item>
    </plurals>
    <plurals name="notification_complete_body_dur">
        <item quantity="one">%1$d clip · %2$s en total · guardado en la Biblioteca</item>
        <item quantity="other">%1$d clips · %2$s en total · guardados en la Biblioteca</item>
    </plurals>
    <plurals name="notification_complete_body_nodur">
        <item quantity="one">%1$d clip guardado en la Biblioteca</item>
        <item quantity="other">%1$d clips guardados en la Biblioteca</item>
    </plurals>
    <plurals name="notification_dots_complete_cd">
        <item quantity="one">%1$d clip completado</item>
        <item quantity="other">Los %1$d clips completados</item>
    </plurals>
</resources>
```

- [ ] **Step 2: Run the full parity test (expect ALL GREEN)**

```powershell
& .\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.locale.SpanishCatalogParityTest" --no-daemon
```
Expected: all 4 tests **PASS**.

- [ ] **Step 3: Run lint again (plurals MissingTranslation clean)**

```powershell
& .\gradlew.bat :app:lintDebug --no-daemon
```
Expected: BUILD SUCCESSFUL, no `MissingQuantity`/`MissingTranslation` for plurals.

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/res/values-es/plurals.xml
git commit -m @'
feat(i18n): Spanish (es) plurals catalog — 10 plurals, staged

one/other categories, format args preserved. Completes the structural parity
(SpanishCatalogParityTest fully green).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
'@
```

---

## Task 4: Add `es` to locales_config.xml

**Files:**
- Modify: `app/src/main/res/xml/locales_config.xml`

- [ ] **Step 1: Add the `es` locale line**

Change the body from:
```xml
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
</locale-config>
```
to:
```xml
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="es" />
</locale-config>
```
Leave the hand-authored comment block intact (it documents why `generateLocaleConfig` stays off).

- [ ] **Step 2: Verify the pseudolocale gate still passes**

```powershell
& .\gradlew.bat :app:checkLocaleConfigNoPseudolocale --no-daemon
```
Expected: BUILD SUCCESSFUL — `es` is not a pseudolocale, so the gate is unaffected.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/res/xml/locales_config.xml
git commit -m @'
feat(i18n): list es in locales_config — system per-app-language exposure (33+)

Spanish now appears in Android Settings > App > Language. In-app picker stays
hidden (SUPPORTED_USER_LOCALES empty) per ADR-0023 staged-locale policy.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
'@
```

---

## Task 5: ADR-0023 amendment + CHANGELOG

**Files:**
- Modify: `docs/adr/0023-localization-locale-picker.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Correct the §Picker-gating sentence**

In `docs/adr/0023-localization-locale-picker.md`, find (under "### Picker gating — no empty promise"):
```
It is `emptyList()` this slice → row hidden. The first real-language slice appends one tag (+ `values-xx/` catalog + one `<locale>` line in `locales_config.xml`) and the row appears.
```
Replace with:
```
It is `emptyList()` this slice → row hidden. Catalog existence is decoupled from in-app reveal (see §Staged Locale Before Reveal): a *staging* slice may add the `values-xx/` catalog and the `<locale>` line in `locales_config.xml` (system-reachable) while `SUPPORTED_USER_LOCALES` still excludes the tag; a later *reveal* slice appends the tag and the in-app row appears.
```

- [ ] **Step 2: Add the §Staged Locale Before Reveal clause**

Immediately after the `### §No Pseudolocale In LocaleConfig — checkLocaleConfigNoPseudolocale` section (before `## Non-goals (Phase B-i)`), insert:
```markdown
### §Staged Locale Before Reveal

A locale catalog MAY ship in `locales_config.xml` (system-reachable on API 33+ via Settings ▸ App ▸ Language) while `AppLocale.SUPPORTED_USER_LOCALES` still excludes its tag (in-app Language row hidden), pending native-review sign-off. This **decouples catalog existence from in-app reveal**: the system per-app-language path may expose a staged (not-yet-reviewed) catalog; the in-app picker's "no empty-promise" invariant is unchanged because that row stays hidden until the catalog is reviewed.

**`es` (Spanish) is the first staged locale** (catalog + `locales_config` line added; `SUPPORTED_USER_LOCALES` unchanged). Reveal is a later slice that appends `"es"` to `SUPPORTED_USER_LOCALES` after sign-off. Structural integrity of a staged catalog is guarded by Android lint (`MissingTranslation`/`ExtraTranslation`) plus the pure-JVM `SpanishCatalogParityTest`; translation polish is the native-review gate.
```

- [ ] **Step 3: Add the CHANGELOG bullet**

In `CHANGELOG.md`, under `## [Unreleased]` → `### Added`, append:
```markdown
- Spanish (`es`) app language — full translated catalog, selectable via Android's system per-app-language settings (Android 13+). In-app language picker remains hidden pending review (ADR-0023 staged-locale policy).
```
(If no `### Added` subsection exists under `## [Unreleased]`, create it above any other subsection.)

- [ ] **Step 4: Commit**

```powershell
git add docs/adr/0023-localization-locale-picker.md CHANGELOG.md
git commit -m @'
docs(i18n): ADR-0023 §Staged Locale Before Reveal + CHANGELOG (es)

Decouple catalog/locales_config from in-app reveal; es is first staged locale.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
'@
```

---

## Task 6: Final full-gate verification

**Files:** none (verification only)

- [ ] **Step 1: Full unit-test suite**

```powershell
& .\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: BUILD SUCCESSFUL, **1394** tests (1390 baseline + 4 new `SpanishCatalogParityTest` methods), 0 failures/errors/skipped. Confirm the exact total from the results XML; the binding gate is **0-0-0**, not the literal count.

- [ ] **Step 2: Full lint + all 27 check\* gates (preBuild path)**

```powershell
& .\gradlew.bat :app:lintDebug --no-daemon
```
Expected: BUILD SUCCESSFUL; `MissingTranslation`/`ExtraTranslation` clean; `checkLocaleConfigNoPseudolocale` + the other 26 gates all pass.

- [ ] **Step 3: Assemble debug APK**

```powershell
& .\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Device staging review (manual, API 33+ — report, do not block merge)**

On a real 33+ device: install the debug APK, open **Settings ▸ Apps ▸ Rova ▸ Language**, confirm **Español** is listed; select it; relaunch Rova; walk the 12 UI surfaces (Record idle/active, History, Settings, Player, Onboarding, Warning sheets, Recovery card, Notification). Confirm: Spanish renders everywhere, no clipped/overflowing labels (target the tight-fit strings flagged in Task 2), format args (counts, durations, filenames) render correctly. Record findings; polish defects feed the deferred native-review pass, not this slice's gate.

---

## Self-review notes

- **Spec coverage:** values-es/strings (T2) ✓ · values-es/plurals (T3) ✓ · locales_config es (T4) ✓ · SUPPORTED_USER_LOCALES untouched (file-structure "untouched" list) ✓ · SpanishCatalogParityTest (T1) ✓ · lint guard (T2 S4, T6 S2) ✓ · ADR-0023 amendment (T5) ✓ · CHANGELOG (T5) ✓ · device review (T6 S4) ✓.
- **Multiset (not sequence) format-arg compare** — refines the spec's "ordered signature" wording so positional reordering passes. Intentional.
- **No Kotlin production churn** — only a test file is added; `AppLocale`/seams untouched (enforced by the file-structure table).
- **Test-count note:** 4 new test methods; exact suite total confirmed from results XML in T6 S1, gate is 0-0-0 not the literal number.
