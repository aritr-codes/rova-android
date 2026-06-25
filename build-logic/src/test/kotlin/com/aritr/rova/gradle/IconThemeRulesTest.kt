package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconThemeRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkVaultExporterNoPublicPublish ────────────────────────────────────

    @Test
    fun vaultExporterNoPublicPublish_passesOnClean() {
        val body = """
            class VaultExporter {
                fun moveToVault(src: File, dst: File) {
                    src.copyTo(dst)
                    src.delete()
                }
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt", body)
        )
        assertNull(RovaGateRules.run("checkVaultExporterNoPublicPublish", files))
    }

    @Test
    fun vaultExporterNoPublicPublish_missingFileReturnsMessage() {
        val msg = RovaGateRules.run("checkVaultExporterNoPublicPublish", emptyList())
        assertTrue(msg!!.startsWith("checkVaultExporterNoPublicPublish: VaultExporter.kt missing"))
    }

    @Test
    fun vaultExporterNoPublicPublish_failsOnMediaStore() {
        val body = "    val x = MediaStore.Images"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt", body)
        )
        val msg = RovaGateRules.run("checkVaultExporterNoPublicPublish", files)
        val expected = "ADR-0025: VaultExporter must not reference any public-publish API " +
            "(vault recordings stay app-private). Offenders:\n" +
            "  VaultExporter.kt:1: val x = MediaStore.Images"
        assertEquals(expected, msg)
    }

    @Test
    fun vaultExporterNoPublicPublish_failsOnMediaScannerConnection() {
        val body = "    MediaScannerConnection.scanFile(ctx, paths, null, null)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt", body)
        )
        val msg = RovaGateRules.run("checkVaultExporterNoPublicPublish", files)
        assertTrue(msg!!.contains("VaultExporter.kt:1:"))
    }

    @Test
    fun vaultExporterNoPublicPublish_skipsCommentLines() {
        // Lines starting with // or * are skipped
        val body = """
            // val x = MediaStore.Video
            * insertPendingRow — never call this
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt", body)
        )
        assertNull(RovaGateRules.run("checkVaultExporterNoPublicPublish", files))
    }

    @Test
    fun vaultExporterNoPublicPublish_reportHardcodesVaultExporterKt() {
        // The report must say "VaultExporter.kt:1:" not the relPath
        val body = "    DIRECTORY_MOVIES"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt", body)
        )
        val msg = RovaGateRules.run("checkVaultExporterNoPublicPublish", files)!!
        assertTrue(msg.contains("VaultExporter.kt:1:"))
    }

    // ─── checkRecordSurfaceNoBlur ─────────────────────────────────────────────

    @Test
    fun recordSurfaceNoBlur_passesOnClean() {
        val body = """
            @Composable
            fun RecordChrome() {
                Box(modifier = Modifier.fillMaxSize()) { }
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordSurfaceNoBlur", files))
    }

    @Test
    fun recordSurfaceNoBlur_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkRecordSurfaceNoBlur", emptyList()))
    }

    @Test
    fun recordSurfaceNoBlur_failsOnBlurInRecordChrome() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/components/RecordChrome.kt"
        val body = "    Modifier.blur(8.dp)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecordSurfaceNoBlur", files)
        assertTrue(msg!!.startsWith("ADR-0028 §2.3 violation"))
        assertTrue(msg.contains("RecordChrome.kt:1:"))
    }

    @Test
    fun recordSurfaceNoBlur_failsOnBlurInRecordScreen() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = "    val effect = RenderEffect"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecordSurfaceNoBlur", files)
        assertTrue(msg!!.contains("RecordScreen.kt:1:"))
    }

    @Test
    fun recordSurfaceNoBlur_skipsCommentLines() {
        val body = """
            // Modifier.blur(8.dp) — DO NOT USE
            * RenderEffect is forbidden here
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordSurfaceNoBlur", files))
    }

    @Test
    fun recordSurfaceNoBlur_passesForNonChromeName() {
        // DualPreviewZone.kt is NOT in the scanned name set — passes even with blur
        val body = "    Modifier.blur(4.dp)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordSurfaceNoBlur", files))
    }

    // ─── checkGlassSurfaceRoleUsage ───────────────────────────────────────────

    @Test
    fun glassSurfaceRoleUsage_passesOnClean() {
        val body = """
            @Composable
            fun SomeScreen() {
                GlassSurface(role = GlassRole.Card) { }
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", files))
    }

    @Test
    fun glassSurfaceRoleUsage_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", emptyList()))
    }

    @Test
    fun glassSurfaceRoleUsage_failsOnBlurOutsideAllowlist() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    Modifier.blur(8.dp)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkGlassSurfaceRoleUsage", files)
        assertTrue(msg!!.startsWith("ADR-0028 §2.1/§5 violation"))
        assertTrue(msg.contains("SomeScreen.kt:1:"))
    }

    @Test
    fun glassSurfaceRoleUsage_skipsCommentLines() {
        val body = """
            // Modifier.blur(4.dp) — old approach
            * RenderEffect removed
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", files))
    }

    @Test
    fun glassSurfaceRoleUsage_passesInAllowlistedGlassSurface() {
        val body = "    Modifier.blur(blurRadius)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/GlassSurface.kt", body)
        )
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", files))
    }

    @Test
    fun glassSurfaceRoleUsage_passesInAllowlistedDualPreviewZone() {
        val body = "    val re = RenderEffect"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt", body)
        )
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", files))
    }

    @Test
    fun glassSurfaceRoleUsage_passesInAllowlistedWarningSheetV3() {
        val body = "    createBlurEffect(4f, 4f, Shader.TileMode.CLAMP)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt", body)
        )
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", files))
    }

    @Test
    fun glassSurfaceRoleUsage_passesInAllowlistedRecoveryCard() {
        val body = "    Modifier.blur(2.dp)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt", body)
        )
        assertNull(RovaGateRules.run("checkGlassSurfaceRoleUsage", files))
    }

    // ─── checkRecordChromeLockSingleSite ──────────────────────────────────────

    @Test
    fun recordChromeLockSingleSite_passesOnClean() {
        val body = """
            @Composable
            fun SettingsScreen() {
                Column { Text("Settings") }
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordChromeLockSingleSite", files))
    }

    @Test
    fun recordChromeLockSingleSite_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkRecordChromeLockSingleSite", emptyList()))
    }

    @Test
    fun recordChromeLockSingleSite_failsOnRequestedOrientationOutsideCanonical() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt"
        val body = "    activity.requestedOrientation = 1"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRecordChromeLockSingleSite", files)
        assertTrue(msg!!.startsWith("ADR-0029 §B″ violation"))
        assertTrue(msg.contains("SettingsScreen.kt:1:"))
    }

    @Test
    fun recordChromeLockSingleSite_passesInCanonicalRecordScreen() {
        val body = "    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordChromeLockSingleSite", files))
    }

    @Test
    fun recordChromeLockSingleSite_skipsBlockComment() {
        // requestedOrientation inside a block comment must be stripped and pass
        val body = "/* activity.requestedOrientation = 1 */"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordChromeLockSingleSite", files))
    }

    @Test
    fun recordChromeLockSingleSite_skipsInlineComment() {
        // requestedOrientation after // is stripped
        val body = "    val x = 1 // requestedOrientation"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRecordChromeLockSingleSite", files))
    }

    // ─── checkLibraryNoManifestWrite ──────────────────────────────────────────

    @Test
    fun libraryNoManifestWrite_passesOnClean() {
        val body = """
            class LibraryViewModel {
                fun loadLibrary() = libraryStore.query()
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/library/LibraryViewModel.kt", body)
        )
        assertNull(RovaGateRules.run("checkLibraryNoManifestWrite", files))
    }

    @Test
    fun libraryNoManifestWrite_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkLibraryNoManifestWrite", emptyList()))
    }

    @Test
    fun libraryNoManifestWrite_failsOnMarkTerminatedInLibrary() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt"
        val body = "    store.markTerminated(id)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkLibraryNoManifestWrite", files)
        assertTrue(msg!!.startsWith("ADR-0030 §2:"))
        assertTrue(msg.contains("ui/library/LibraryScreen.kt:1:"))
    }

    @Test
    fun libraryNoManifestWrite_failsOnSetExportPendingInHistoryScreen() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt"
        val body = "    store.setExportPending(id)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkLibraryNoManifestWrite", files)
        assertTrue(msg!!.contains("ui/screens/HistoryScreen.kt:1:"))
    }

    @Test
    fun libraryNoManifestWrite_skipsCommentLines() {
        val body = """
            // store.markTerminated(id) — removed
            * appendSegment call here was wrong
            /* setExportPending was here */
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkLibraryNoManifestWrite", files))
    }

    @Test
    fun libraryNoManifestWrite_skipsInlineCommentSuffix() {
        // A commented-out example after // on code line must not false-fail
        val body = "    val x = 1 // store.markTerminated(id)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkLibraryNoManifestWrite", files))
    }

    @Test
    fun libraryNoManifestWrite_passesForNonScopedFile() {
        // A file outside ui/library/ and not History/Library in ui/screens/ is not scoped
        val body = "    store.markTerminated(id)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModel.kt", body)
        )
        assertNull(RovaGateRules.run("checkLibraryNoManifestWrite", files))
    }

    // ─── checkSemanticIconNoRawAlpha ──────────────────────────────────────────

    @Test
    fun semanticIconNoRawAlpha_passesOnClean() {
        val body = """
            SemanticIcon(
                role = IconRole.OnSurface,
                status = IconStatus.Active,
            )
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/HomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSemanticIconNoRawAlpha", files))
    }

    @Test
    fun semanticIconNoRawAlpha_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkSemanticIconNoRawAlpha", emptyList()))
    }

    @Test
    fun semanticIconNoRawAlpha_failsOnRawColorTint() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    Icon(tint = Color.Red)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSemanticIconNoRawAlpha", files)
        assertTrue(msg!!.startsWith("ADR-0031 §4 violation"))
        assertTrue(msg.contains("SomeScreen.kt:1:"))
    }

    @Test
    fun semanticIconNoRawAlpha_catchesWrappedColorOnNextLine() {
        // tint on one line, Color( on the second line — within 3-line window
        val body = "    Icon(\n        tint =\n            Color(0xFF0000)\n    )"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        val msg = RovaGateRules.run("checkSemanticIconNoRawAlpha", files)
        assertTrue(msg != null)
    }

    @Test
    fun semanticIconNoRawAlpha_skipsCommentLines() {
        val body = """
            // tint = Color.Red — old approach
            * tint = Color(0xFF) not allowed
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSemanticIconNoRawAlpha", files))
    }

    @Test
    fun semanticIconNoRawAlpha_passesWithOptOut() {
        val body = "    Icon(tint = Color.Red) // semanticicon-opt-out: preview only"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSemanticIconNoRawAlpha", files))
    }

    @Test
    fun semanticIconNoRawAlpha_passesInCanonicalSemanticIcon() {
        val body = "    Icon(tint = Color.Red)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/components/SemanticIcon.kt", body)
        )
        assertNull(RovaGateRules.run("checkSemanticIconNoRawAlpha", files))
    }

    // ─── checkStatusColorLocked ───────────────────────────────────────────────

    @Test
    fun statusColorLocked_passesOnClean() {
        val body = """
            val color = RovaSemantics.recording
            Icon(tint = color)
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkStatusColorLocked", files))
    }

    @Test
    fun statusColorLocked_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkStatusColorLocked", emptyList()))
    }

    @Test
    fun statusColorLocked_failsOnCopyMutation() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    val c = RovaSemantics.recording.copy(alpha = 0.5f)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkStatusColorLocked", files)
        val expected = "ADR-0031 §3 violation: a locked RovaSemantics status color is mutated (.copy) at the " +
            "call-site. Status colors render exact, at full locked opacity (and are paired with " +
            "shape, WCAG 1.4.1). Use the color directly, or vary emphasis with shape/size.\n" +
            "Offenders:\n" +
            "  ${relPath}:1: val c = RovaSemantics.recording.copy(alpha = 0.5f)"
        assertEquals(expected, msg)
    }

    @Test
    fun statusColorLocked_skipsCommentLines() {
        val body = """
            // RovaSemantics.recording.copy(alpha = 0.5f) — do not do this
            * RovaSemantics.warning.copy(red = 1f)
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkStatusColorLocked", files))
    }

    // ─── checkRovaGlyphHome ───────────────────────────────────────────────────

    @Test
    fun rovaGlyphHome_passesOnClean() {
        val body = """
            val icon = RovaIcons.Play
            SemanticIcon(role = IconRole.Action)
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRovaGlyphHome", files))
    }

    @Test
    fun rovaGlyphHome_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkRovaGlyphHome", emptyList()))
    }

    @Test
    fun rovaGlyphHome_failsOnBuilderOutsideHome() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    val v = ImageVector.Builder("
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkRovaGlyphHome", files)
        assertTrue(msg!!.startsWith("ADR-0031 §5 violation"))
        assertTrue(msg.contains("SomeScreen.kt:1"))
    }

    @Test
    fun rovaGlyphHome_catchesBuilderSplitAcrossLines() {
        // ImageVector.\n    Builder( must still be caught
        val body = "    val v = ImageVector\n        .Builder("
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        val msg = RovaGateRules.run("checkRovaGlyphHome", files)
        assertTrue(msg != null)
    }

    @Test
    fun rovaGlyphHome_passesWhenBuilderInsideBlockComment() {
        // ImageVector.Builder( inside a block comment is stripped and passes
        val body = "/* val v = ImageVector.Builder( */"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRovaGlyphHome", files))
    }

    @Test
    fun rovaGlyphHome_passesWhenBuilderInsideInlineComment() {
        val body = "    // val v = ImageVector.Builder("
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkRovaGlyphHome", files))
    }

    @Test
    fun rovaGlyphHome_passesInCanonicalRovaGlyphs() {
        val body = "    val Play = ImageVector.Builder("
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt", body)
        )
        assertNull(RovaGateRules.run("checkRovaGlyphHome", files))
    }

    // ─── checkSingleColorSchemeSource ─────────────────────────────────────────

    @Test
    fun singleColorSchemeSource_passesOnClean() {
        val body = """
            @Composable
            fun SomeScreen() {
                MaterialTheme(typography = typography) {
                    content()
                }
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }

    @Test
    fun singleColorSchemeSource_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", emptyList()))
    }

    @Test
    fun singleColorSchemeSource_failsOnDarkColorScheme() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    val s = darkColorScheme("
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSingleColorSchemeSource", files)
        assertTrue(msg!!.startsWith("ADR-0028 amendment:"))
        assertTrue(msg.contains("SomeScreen.kt:1"))
    }

    @Test
    fun singleColorSchemeSource_failsOnLightColorScheme() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    val s = lightColorScheme("
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSingleColorSchemeSource", files)
        assertTrue(msg!!.startsWith("ADR-0028 amendment:"))
    }

    @Test
    fun singleColorSchemeSource_failsOnMaterialThemeWithColorSchemeArg() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    MaterialTheme(colorScheme = myScheme) { }"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSingleColorSchemeSource", files)
        assertTrue(msg!!.startsWith("ADR-0028 amendment:"))
    }

    @Test
    fun singleColorSchemeSource_passesWithOptOut() {
        val body = "    // colorscheme-source-opt-out: test\n    val s = darkColorScheme("
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }

    @Test
    fun singleColorSchemeSource_passesWhenInsideBlockComment() {
        // darkColorScheme( inside a block comment is stripped and passes
        val body = "/* val s = darkColorScheme( */"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }

    @Test
    fun singleColorSchemeSource_passesWhenInsideStringLiteral() {
        // darkColorScheme( inside a string must not fire
        val body = "    val s = \"darkColorScheme(\""
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }

    @Test
    fun singleColorSchemeSource_passesInCanonicalThemeKt() {
        val body = "    val scheme = darkColorScheme("
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/Theme.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }

    @Test
    fun singleColorSchemeSource_passesInCanonicalPaletteColorScheme() {
        val body = "    return lightColorScheme("
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/theme/PaletteColorScheme.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }

    @Test
    fun singleColorSchemeSource_passesForMaterialThemeWithoutColorSchemeArg() {
        // MaterialTheme( without colorScheme= in balanced args is fine
        val body = "    MaterialTheme(typography = myType, shapes = myShapes) { }"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSingleColorSchemeSource", files))
    }
}
