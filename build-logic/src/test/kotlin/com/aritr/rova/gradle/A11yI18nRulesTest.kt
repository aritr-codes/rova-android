package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class A11yI18nRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkNoHardcodedUiStrings ────────────────────────────────────────────

    @Test
    fun noHardcodedUiStrings_passesOnClean() {
        // stringResource / getString calls are not flagged.
        val body = """
            Text(text = stringResource(R.string.title))
            Text(stringResource(R.string.label))
            contentDescription = getString(R.string.desc)
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoHardcodedUiStrings", files))
    }

    @Test
    fun noHardcodedUiStrings_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkNoHardcodedUiStrings", emptyList()))
    }

    @Test
    fun noHardcodedUiStrings_failsOnTextLiteral() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt"
        val body = """    Text("Hello World")"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoHardcodedUiStrings", files)
        val expected = "Hardcoded user-facing string literal(s) found at Compose " +
            "`Text(`/`contentDescription =` call sites. User-facing copy " +
            "must live in `res/values/strings.xml` and be read via " +
            "`stringResource(...)` / `getString(...)` (ADR-0022 §No " +
            "Hardcoded UI Strings). For a genuinely non-user-facing " +
            "literal or @Preview-only sample data, add a " +
            "`// i18n-opt-out: <reason>` marker on the line to skip it. " +
            "Offenders:\n" +
            "  $relPath:1: Text(\"Hello World\")"
        assertEquals(expected, msg)
    }

    @Test
    fun noHardcodedUiStrings_failsOnContentDescLiteral() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt"
        val body = """    contentDescription = "Close button""""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoHardcodedUiStrings", files)
        val expected = "Hardcoded user-facing string literal(s) found at Compose " +
            "`Text(`/`contentDescription =` call sites. User-facing copy " +
            "must live in `res/values/strings.xml` and be read via " +
            "`stringResource(...)` / `getString(...)` (ADR-0022 §No " +
            "Hardcoded UI Strings). For a genuinely non-user-facing " +
            "literal or @Preview-only sample data, add a " +
            "`// i18n-opt-out: <reason>` marker on the line to skip it. " +
            "Offenders:\n" +
            "  $relPath:1: contentDescription = \"Close button\""
        assertEquals(expected, msg)
    }

    @Test
    fun noHardcodedUiStrings_skipsI18nOptOut() {
        // Line with i18n-opt-out is not flagged even if it has a literal.
        val body = """    Text("Preview only") // i18n-opt-out: @Preview sample"""
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoHardcodedUiStrings", files))
    }

    @Test
    fun noHardcodedUiStrings_skipsCommentLines() {
        // Lines inside // or /* */ comments are not flagged (CommentStripper).
        val body = """
            // Text("debug label")
            /* * contentDescription = "doc example" */
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoHardcodedUiStrings", files))
    }

    @Test
    fun noHardcodedUiStrings_detectsAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt"
        val body = """    */ Text("hi")"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoHardcodedUiStrings", files)
        assert(msg != null && msg.startsWith("Hardcoded user-facing string literal(s) found at Compose"))
    }

    // ─── checkLocaleConfigNoPseudolocale ──────────────────────────────────────

    @Test
    fun localeConfigNoPseudolocale_passesOnClean() {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                <locale android:name="en" />
                <locale android:name="es" />
            </locale-config>
        """.trimIndent()
        val files = listOf(
            src("app/src/main/res/xml/locales_config.xml", body)
        )
        assertNull(RovaGateRules.run("checkLocaleConfigNoPseudolocale", files))
    }

    @Test
    fun localeConfigNoPseudolocale_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkLocaleConfigNoPseudolocale", emptyList()))
    }

    @Test
    fun localeConfigNoPseudolocale_failsOnEnXA() {
        val relPath = "app/src/main/res/xml/locales_config.xml"
        val body = """
            <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                <locale android:name="en" />
                <locale android:name="en-rXA" />
            </locale-config>
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkLocaleConfigNoPseudolocale", files)
        val expected = "Pseudolocale tag(s) found in locales_config.xml. Pseudolocales " +
            "(en_XA / ar_XB) are a DEBUG-ONLY QA tool and must never be " +
            "advertised as a user language (ADR-0023 §No Pseudolocale In " +
            "LocaleConfig). Remove them; keep generateLocaleConfig OFF. " +
            "Offenders:\n" +
            "  $relPath:3: <locale android:name=\"en-rXA\" />"
        assertEquals(expected, msg)
    }

    @Test
    fun localeConfigNoPseudolocale_failsOnArXB() {
        val relPath = "app/src/main/res/xml/locales_config.xml"
        // ar-XB (without r) should also match
        val body = """    <locale android:name="ar-XB" />"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkLocaleConfigNoPseudolocale", files)
        val expected = "Pseudolocale tag(s) found in locales_config.xml. Pseudolocales " +
            "(en_XA / ar_XB) are a DEBUG-ONLY QA tool and must never be " +
            "advertised as a user language (ADR-0023 §No Pseudolocale In " +
            "LocaleConfig). Remove them; keep generateLocaleConfig OFF. " +
            "Offenders:\n" +
            "  $relPath:1: <locale android:name=\"ar-XB\" />"
        assertEquals(expected, msg)
    }

    // ─── checkUserCopyVocabulary ──────────────────────────────────────────────

    @Test
    fun userCopyVocabulary_passesOnClean() {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Rova</string>
                <string name="record_start">Start recording</string>
                <string name="clip_count">3 clips</string>
            </resources>
        """.trimIndent()
        val files = listOf(
            src("app/src/main/res/values/strings.xml", body)
        )
        assertNull(RovaGateRules.run("checkUserCopyVocabulary", files))
    }

    @Test
    fun userCopyVocabulary_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkUserCopyVocabulary", emptyList()))
    }

    @Test
    fun userCopyVocabulary_failsOnBannedVocab() {
        val relPath = "app/src/main/res/values/strings.xml"
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="loop_label">loops completed</string>
            </resources>
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkUserCopyVocabulary", files)
        // Old gate reports the bare module-relative path (no "app/" prefix).
        val expected = "ADR-0029 §C: banned vocabulary in user copy (use clip/session):\n" +
            "src/main/res/values/strings.xml: loop_label = loops completed"
        assertEquals(expected, msg)
    }

    @Test
    fun userCopyVocabulary_failsOnCountMismatch() {
        // Unclosed tag creates mismatch between <string count and nameRe matches.
        val relPath = "app/src/main/res/values/strings.xml"
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="ok_label">OK</string>
                <string name="broken_no_close">value without close
            </resources>
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkUserCopyVocabulary", files)
        // declared=2, matches=1 (the broken one doesn't close)
        val expected = "checkUserCopyVocabulary: parser matched 1/2 strings in " +
            "src/main/res/values/strings.xml — fix the regex"
        assertEquals(expected, msg)
    }

    // ─── checkA11yAnimationGated ──────────────────────────────────────────────

    @Test
    fun a11yAnimationGated_passesOnClean() {
        // File uses rememberInfiniteTransition AND reads the seam.
        val body = """
            val rm = rememberReduceMotion()
            val transition = rememberInfiniteTransition()
            val alpha by transition.animateFloat(
                initialValue = if (rm) 1f else 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1000))
            )
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/components/PulseAnim.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yAnimationGated", files))
    }

    @Test
    fun a11yAnimationGated_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkA11yAnimationGated", emptyList()))
    }

    @Test
    fun a11yAnimationGated_failsWhenSeamAbsent() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/components/PulseAnim.kt"
        val body = """
            val transition = rememberInfiniteTransition()
            val alpha by transition.animateFloat(tween(1000))
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yAnimationGated", files)
        val expected = "ADR-0020 §Decision-3 violation (WCAG 2.2 AA — SC 2.3.3 Animation " +
            "from Interactions / SC 2.2.2 Pause, Stop, Hide): looping/" +
            "auto-playing animation primitive(s) used without a " +
            "reduced-motion guard in the same file. Every file that uses " +
            "`rememberInfiniteTransition` / `infiniteRepeatable` must also " +
            "read the reduced-motion seam (`rememberReduceMotion()` or " +
            "`ReducedMotion.isReduced`) and select a static value when " +
            "motion is reduced. For a genuinely static or @Preview-only " +
            "animation, add `// a11y-opt-out: <reason>` on the primitive " +
            "line.\nOffenders:\n" +
            "  $relPath:1: val transition = rememberInfiniteTransition()"
        assertEquals(expected, msg)
    }

    @Test
    fun a11yAnimationGated_skipsOptOutLine() {
        // The triggering line has a11y-opt-out so it is not counted as a trigger.
        val body = """
            val transition = rememberInfiniteTransition() // a11y-opt-out: static brand splash
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/components/BrandAnim.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yAnimationGated", files))
    }

    @Test
    fun a11yAnimationGated_skipsCommentLines() {
        // Commented-out primitives inside // or /* */ are not triggers (CommentStripper).
        val body = """
            // val t = rememberInfiniteTransition()
            /* * infiniteRepeatable(tween(500)) */
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/components/BrandAnim.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yAnimationGated", files))
    }

    @Test
    fun a11yAnimationGated_detectsAfterBlockCommentClose() {
        // `*/ <code>` with no seam — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/ui/components/BrandAnim.kt"
        val body = """    */ rememberInfiniteTransition()"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yAnimationGated", files)
        assert(msg != null && msg.startsWith("ADR-0020 §Decision-3 violation"))
    }

    // ─── checkA11yClickableHasRole ────────────────────────────────────────────

    @Test
    fun a11yClickableHasRole_passesOnClean() {
        // .clickable with role in the forward window.
        val body = """
            Row(
                modifier = Modifier
                    .semantics { role = Role.Button }
                    .clickable { onClick() }
            ) { Text(stringResource(R.string.label)) }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yClickableHasRole", files))
    }

    @Test
    fun a11yClickableHasRole_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkA11yClickableHasRole", emptyList()))
    }

    @Test
    fun a11yClickableHasRole_failsWhenRoleAbsent() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        // .clickable with no role = Role. anywhere in the 15+20 line window.
        val body = "    Modifier.clickable { doSomething() }"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yClickableHasRole", files)
        val expected = "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 4.1.2 Name, " +
            "Role, Value): custom Modifier.clickable / combinedClickable " +
            "used without an accessibility role on the same modifier " +
            "chain. A custom clickable container (Row/Box/Surface/Column) " +
            "must declare a role so TalkBack announces it as actionable — " +
            "either `clickable(role = Role.Button, …)` or an adjacent " +
            "`.semantics { role = … }` / `.clearAndSetSemantics { role = … }`. " +
            "Material Button/IconButton supply a role already. For toggles/" +
            "selections use toggleable/selectable (out of scope here). For a " +
            "genuinely role-exempt case, add `// a11y-opt-out: <reason>` " +
            "(reason required) on the clickable line.\nOffenders:\n" +
            "  $relPath:1: Modifier.clickable { doSomething() }"
        assertEquals(expected, msg)
    }

    @Test
    fun a11yClickableHasRole_skipsOptOut() {
        // a11y-opt-out with non-empty reason skips the clickable line.
        val body = """    Modifier.clickable { doSomething() } // a11y-opt-out: container semantics on parent"""
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yClickableHasRole", files))
    }

    @Test
    fun a11yClickableHasRole_passesWhenRoleInBackWindow() {
        // role = Role.Tab appears 10 lines BEFORE the clickable — within backWindow=15.
        val lines = buildList {
            add("    .semantics { role = Role.Tab }")
            repeat(9) { add("    // filler line") }
            add("    .clickable { selectTab() }")
        }
        val body = lines.joinToString("\n")
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yClickableHasRole", files))
    }

    @Test
    fun a11yClickableHasRole_detectsAfterBlockCommentClose() {
        // `*/ <code>` with no role in window — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = """    */ .clickable { }"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yClickableHasRole", files)
        assert(msg != null && msg.startsWith("ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 4.1.2 Name,"))
    }

    // ─── checkA11yTargetSizeToken ─────────────────────────────────────────────

    @Test
    fun a11yTargetSizeToken_passesOnClean() {
        // All curated tokens >= 24.dp.
        val body = """
            object RovaTokens {
                val camControlSize = 30.dp
                val stepperButtonSize = 27.dp
                val primaryActionSize = 64.dp
                val stopActionSize = 72.dp
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yTargetSizeToken", files))
    }

    @Test
    fun a11yTargetSizeToken_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkA11yTargetSizeToken", emptyList()))
    }

    @Test
    fun a11yTargetSizeToken_failsWhenTokenBelowFloor() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt"
        val body = "    val camControlSize = 16.dp"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yTargetSizeToken", files)
        val expected = "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
            "Size (Minimum)): an interactive-target size token is below " +
            "the 24.dp accessibility floor. A tappable control's size " +
            "token (button diameter / tap box / FAB / tile) " +
            "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
            "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
            "floor is met by call-site padding/`heightIn` rather than the " +
            "token itself — add `// a11y-opt-out: <reason>` (reason " +
            "required) on the token line.\nOffenders:\n" +
            "  $relPath:1: val camControlSize = 16.dp"
        assertEquals(expected, msg)
    }

    @Test
    fun a11yTargetSizeToken_passesWhenExactlyAtFloor() {
        // 24.dp is exactly at the floor — must pass.
        val body = "    val camControlSize = 24.dp"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yTargetSizeToken", files))
    }

    @Test
    fun a11yTargetSizeToken_skipsOptOut() {
        // a11y-opt-out with non-empty reason skips the token line.
        val body = "    val camControlSize = 16.dp // a11y-opt-out: touch floor via call-site padding"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yTargetSizeToken", files))
    }

    @Test
    fun a11yTargetSizeToken_skipsBlockComment() {
        // Token inside a block comment must not fail the gate.
        val body = """
            /*
             * val camControlSize = 16.dp — old value, do not restore
             */
            val camControlSize = 30.dp
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yTargetSizeToken", files))
    }

    @Test
    fun a11yTargetSizeToken_skipsNonCuratedToken() {
        // A token NOT in the curated set is never flagged even if below 24.dp.
        val body = "    val statusDotSize = 6.dp"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt", body)
        )
        assertNull(RovaGateRules.run("checkA11yTargetSizeToken", files))
    }

    @Test
    fun a11yTargetSizeToken_catchesTokenAfterInlineBlockComment() {
        // F2 hole: `/* … */` then a sub-floor token on one line used to be
        // dropped wholesale by the startsWith("/*") skip.
        val relPath = "app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt"
        val body = "    /* tweaked */ val camControlSize = 20.dp"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yTargetSizeToken", files)
        val expected = "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
            "Size (Minimum)): an interactive-target size token is below " +
            "the 24.dp accessibility floor. A tappable control's size " +
            "token (button diameter / tap box / FAB / tile) " +
            "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
            "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
            "floor is met by call-site padding/`heightIn` rather than the " +
            "token itself — add `// a11y-opt-out: <reason>` (reason " +
            "required) on the token line.\nOffenders:\n" +
            "  $relPath:1: /* tweaked */ val camControlSize = 20.dp"
        assertEquals(expected, msg)
    }

    @Test
    fun a11yTargetSizeToken_stringSlashStarDoesNotDisableGate() {
        // F1 hole: a `/*` inside a string literal used to set inBlock=true and
        // never clear, silently disabling the gate for the rest of the file.
        val relPath = "app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt"
        val body = "    val label = \"a /* not a comment\"\n    val camControlSize = 18.dp"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yTargetSizeToken", files)
        val expected = "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
            "Size (Minimum)): an interactive-target size token is below " +
            "the 24.dp accessibility floor. A tappable control's size " +
            "token (button diameter / tap box / FAB / tile) " +
            "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
            "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
            "floor is met by call-site padding/`heightIn` rather than the " +
            "token itself — add `// a11y-opt-out: <reason>` (reason " +
            "required) on the token line.\nOffenders:\n" +
            "  $relPath:2: val camControlSize = 18.dp"
        assertEquals(expected, msg)
    }

    // ─── round-4 hole-close: reduced-motion seam only in a comment ─────────────

    @Test
    fun a11yAnimationGated_failsWhenSeamOnlyInComment() {
        // round-4: the reduced-motion seam present only in a comment must NOT satisfy
        // the require (false-PASS on raw lines.any before the strippedLines migration).
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = """
            // rememberReduceMotion() is read by the parent composable
            val t = rememberInfiniteTransition()
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yAnimationGated", files)
        assert(msg != null && msg.startsWith("ADR-0020 §Decision-3 violation"))
    }
}
