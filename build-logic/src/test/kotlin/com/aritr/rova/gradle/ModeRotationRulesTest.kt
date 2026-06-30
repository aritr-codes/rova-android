package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeRotationRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkPresetNoOrientation ─────────────────────────────────────────────

    @Test
    fun presetNoOrientation_passesOnClean() {
        // RovaPreset param block has no mode/orientation field; BuiltInPresets has no such property.
        val settingsBody = """
            data class RovaSettings(val foo: Int = 0)
            data class RovaPreset(
                val durationSeconds: Int = 60,
                val segmentDurationSeconds: Int = 30
            )
        """.trimIndent()
        val builtInsBody = """
            object BuiltInPresets {
                val DEFAULT = RovaPreset()
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", settingsBody),
            src("app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt", builtInsBody),
        )
        assertNull(RovaGateRules.run("checkPresetNoOrientation", files))
    }

    @Test
    fun presetNoOrientation_failsOnEmptyInput() {
        // No RovaSettings.kt in the file list => source-missing message.
        val msg = RovaGateRules.run("checkPresetNoOrientation", emptyList())
        val expected = "checkPresetNoOrientation: source missing: " +
            "src/main/java/com/aritr/rova/data/RovaSettings.kt"
        assertEquals(expected, msg)
    }

    @Test
    fun presetNoOrientation_failsWhenRovaPresetHasModeField() {
        // RovaPreset constructor has "mode :" inside its param block.
        val settingsBody = """
            data class RovaPreset(
                val durationSeconds: Int = 60,
                val mode: String = "portrait"
            )
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", settingsBody),
        )
        val msg = RovaGateRules.run("checkPresetNoOrientation", files)
        val expected =
            "checkPresetNoOrientation: RovaPreset must not declare a mode/orientation field (ADR-0026)."
        assertEquals(expected, msg)
    }

    @Test
    fun presetNoOrientation_failsWhenRovaPresetHasOrientationField() {
        // RovaPreset constructor has "orientation :" inside its param block.
        val settingsBody = """
            data class RovaPreset(
                val durationSeconds: Int = 60,
                val orientation: Int = 0
            )
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", settingsBody),
        )
        val msg = RovaGateRules.run("checkPresetNoOrientation", files)
        val expected =
            "checkPresetNoOrientation: RovaPreset must not declare a mode/orientation field (ADR-0026)."
        assertEquals(expected, msg)
    }

    @Test
    fun presetNoOrientation_failsWhenBuiltInsHasModeProperty() {
        // BuiltInPresets.kt declares a val mode property.
        val settingsBody = """
            data class RovaPreset(val durationSeconds: Int = 60)
        """.trimIndent()
        val builtInsBody = """
            object BuiltInPresets {
                val mode = "portrait"
                val DEFAULT = RovaPreset()
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", settingsBody),
            src("app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt", builtInsBody),
        )
        val msg = RovaGateRules.run("checkPresetNoOrientation", files)
        val expected =
            "checkPresetNoOrientation: BuiltInPresets must not declare a mode/orientation property (ADR-0026)."
        assertEquals(expected, msg)
    }

    @Test
    fun presetNoOrientation_failsWhenBuiltInsHasOrientationProperty() {
        // BuiltInPresets.kt declares a var orientation property.
        val settingsBody = """
            data class RovaPreset(val durationSeconds: Int = 60)
        """.trimIndent()
        val builtInsBody = """
            object BuiltInPresets {
                var orientation = 0
                val DEFAULT = RovaPreset()
            }
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", settingsBody),
            src("app/src/main/java/com/aritr/rova/data/BuiltInPresets.kt", builtInsBody),
        )
        val msg = RovaGateRules.run("checkPresetNoOrientation", files)
        val expected =
            "checkPresetNoOrientation: BuiltInPresets must not declare a mode/orientation property (ADR-0026)."
        assertEquals(expected, msg)
    }

    @Test
    fun presetNoOrientation_passesInExcludedFileSettings() {
        // Passing because RovaSettings.kt is the CHECKED file here, but using
        // mode outside RovaPreset param block is legal (no mode: in ctorParam scope).
        val settingsBody = """
            data class RovaSettings(val mode: String = "Single")
            data class RovaPreset(val durationSeconds: Int = 60)
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", settingsBody),
        )
        // mode is in RovaSettings, not RovaPreset — the gate only checks the RovaPreset block.
        assertNull(RovaGateRules.run("checkPresetNoOrientation", files))
    }

    // ─── checkNoLegacyModeStrings ─────────────────────────────────────────────

    @Test
    fun noLegacyModeStrings_passesOnClean() {
        // No legacy mode string literals in a non-allowlisted file.
        val body = """
            val topology = CaptureTopology.Single
            val label = "recording"
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoLegacyModeStrings", files))
    }

    @Test
    fun noLegacyModeStrings_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkNoLegacyModeStrings", emptyList()))
    }

    @Test
    fun noLegacyModeStrings_failsOnPortraitLiteral() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = """    val s = "Portrait""""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoLegacyModeStrings", files)
        val rel = "ui/screens/RecordScreen.kt"
        val expected = "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
            "$rel:1: val s = \"Portrait\""
        assertEquals(expected, msg)
    }

    @Test
    fun noLegacyModeStrings_failsOnLandscapeLiteral() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = """    val s = "Landscape""""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoLegacyModeStrings", files)
        val rel = "ui/screens/RecordScreen.kt"
        val expected = "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
            "$rel:1: val s = \"Landscape\""
        assertEquals(expected, msg)
    }

    @Test
    fun noLegacyModeStrings_failsOnPortraitLandscapeLiteral() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = """    val s = "PortraitLandscape""""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoLegacyModeStrings", files)
        val rel = "ui/screens/RecordScreen.kt"
        val expected = "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
            "$rel:1: val s = \"PortraitLandscape\""
        assertEquals(expected, msg)
    }

    @Test
    fun noLegacyModeStrings_skipsCommentLines() {
        // Lines starting with // * or slash-star are skipped.
        val body = """
            // val mode = "Portrait"
            /*
             * "Landscape" — legacy
             */
            /* "PortraitLandscape" was the old value */
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoLegacyModeStrings", files))
    }

    @Test
    fun noLegacyModeStrings_passesInExcludedFileSessionManifest() {
        // SessionManifest.kt is in the allowlist — "Portrait" there is legal.
        val body = """val legacyMode = "Portrait""""
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/SessionManifest.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoLegacyModeStrings", files))
    }

    @Test
    fun noLegacyModeStrings_passesInExcludedFileModeMigration() {
        val body = """val old = "Landscape""""
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/ModeMigration.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoLegacyModeStrings", files))
    }

    @Test
    fun noLegacyModeStrings_passesInExcludedFileRovaSettings() {
        val body = """val migration = "PortraitLandscape""""
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/RovaSettings.kt", body)
        )
        assertNull(RovaGateRules.run("checkNoLegacyModeStrings", files))
    }

    @Test
    fun noLegacyModeStrings_catchesLiteralAfterInlineBlockComment() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    /* legacy */ val m = \"Portrait\""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoLegacyModeStrings", files)
        val expected = "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
            "ui/screens/SomeScreen.kt:1: /* legacy */ val m = \"Portrait\""
        assertEquals(expected, msg)
    }

    // ─── checkSetTargetRotationBoundaryOnly ───────────────────────────────────

    @Test
    fun setTargetRotationBoundaryOnly_passesOnClean() {
        // No setTargetRotation in a non-service file.
        val body = """
            val rotation = 0
            camera.startRecording()
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkSetTargetRotationBoundaryOnly", files))
    }

    @Test
    fun setTargetRotationBoundaryOnly_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkSetTargetRotationBoundaryOnly", emptyList()))
    }

    @Test
    fun setTargetRotationBoundaryOnly_failsOnViolationInUiFile() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = "    view.setTargetRotation(0)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSetTargetRotationBoundaryOnly", files)
        val rel = "ui/screens/RecordScreen.kt"
        val expected = "ADR-0029 §3: setTargetRotation outside boundary-owning files:\n$rel:1"
        assertEquals(expected, msg)
    }

    @Test
    fun setTargetRotationBoundaryOnly_failsEvenOnCommentedLine() {
        // Gate has NO comment-skip — commented calls are also forbidden.
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = "    // view.setTargetRotation(90)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSetTargetRotationBoundaryOnly", files)
        val rel = "ui/screens/RecordScreen.kt"
        val expected = "ADR-0029 §3: setTargetRotation outside boundary-owning files:\n$rel:1"
        assertEquals(expected, msg)
    }

    @Test
    fun setTargetRotationBoundaryOnly_passesInExcludedFileService() {
        // RovaRecordingService.kt is the boundary owner — allowed.
        val body = "    videoCapture.setTargetRotation(surface.display.rotation)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt", body)
        )
        assertNull(RovaGateRules.run("checkSetTargetRotationBoundaryOnly", files))
    }

    @Test
    fun setTargetRotationBoundaryOnly_passesInExcludedFileDualrecord() {
        // Files under service/dualrecord/ are boundary owners — allowed.
        val body = "    recorder.setTargetRotation(rotation)"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/service/dualrecord/DualVideoRecorder.kt", body)
        )
        assertNull(RovaGateRules.run("checkSetTargetRotationBoundaryOnly", files))
    }

    @Test
    fun setTargetRotation_inSingleRecordPackage_isAllowed() {
        val src = SourceFile(
            relPath = "com/aritr/rova/service/singlerecord/SingleVideoRecorder.kt",
            lines = listOf("        videoCapture = VideoCapture.Builder(recorder).setTargetRotation(rot).build()"),
            text = "        videoCapture = VideoCapture.Builder(recorder).setTargetRotation(rot).build()\n",
        )
        assertNull(ruleSetTargetRotationBoundaryOnly(listOf(src)))
    }

    @Test
    fun setTargetRotation_outsideBoundary_stillRedFires() {
        val src = SourceFile(
            relPath = "com/aritr/rova/ui/screens/RecordScreen.kt",
            lines = listOf("    preview.setTargetRotation(rot)"),
            text = "    preview.setTargetRotation(rot)\n",
        )
        val msg = ruleSetTargetRotationBoundaryOnly(listOf(src))
        assertNotNull(msg)
        assertTrue(msg!!.startsWith("ADR-0029 §3: setTargetRotation outside boundary-owning files:"))
    }

    // ─── checkFrontBackCapabilityGated ────────────────────────────────────────

    @Test
    fun frontBackCapabilityGated_passesOnClean() {
        // No FrontBack reference in a non-allowlisted file.
        val body = """
            val topology = CaptureTopology.Single
            val label = "dual"
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkFrontBackCapabilityGated", files))
    }

    @Test
    fun frontBackCapabilityGated_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkFrontBackCapabilityGated", emptyList()))
    }

    @Test
    fun frontBackCapabilityGated_failsOnFrontBackInUiFile() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt"
        val body = "    val x = FrontBack"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFrontBackCapabilityGated", files)
        val rel = "ui/screens/RecordScreen.kt"
        val expected = "ADR-0029 §5: FrontBack outside the capability-gated registry:\n$rel:1"
        assertEquals(expected, msg)
    }

    @Test
    fun frontBackCapabilityGated_skipsCommentLines() {
        // Lines starting with // * or slash-star are skipped.
        val body = """
            // FrontBack is not supported
            /*
             * FrontBack — deferred
             */
            /* val fb = FrontBack */
        """.trimIndent()
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt", body)
        )
        assertNull(RovaGateRules.run("checkFrontBackCapabilityGated", files))
    }

    @Test
    fun frontBackCapabilityGated_passesInExcludedFileCaptureTopology() {
        // CaptureTopology.kt is the declaration site — allowed.
        val body = "    FrontBack;"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/data/CaptureTopology.kt", body)
        )
        assertNull(RovaGateRules.run("checkFrontBackCapabilityGated", files))
    }

    @Test
    fun frontBackCapabilityGated_passesInExcludedFileCaptureModes() {
        // CaptureModes.kt is the capability-gate registry — allowed.
        val body = "    CaptureTopology.FrontBack -> showFrontBackPicker()"
        val files = listOf(
            src("app/src/main/java/com/aritr/rova/ui/screens/CaptureModes.kt", body)
        )
        assertNull(RovaGateRules.run("checkFrontBackCapabilityGated", files))
    }

    @Test
    fun frontBackCapabilityGated_catchesAfterInlineBlockComment() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    /* note */ val t = FrontBack"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFrontBackCapabilityGated", files)
        val expected = "ADR-0029 §5: FrontBack outside the capability-gated registry:\n" +
            "ui/screens/SomeScreen.kt:1"
        assertEquals(expected, msg)
    }

    // ── checkAeFpsRangeCapabilityGated (ADR-0034) ──────────────────────────

    private fun svc(text: String) = listOf(
        SourceFile("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            text.split("\n"), text)
    )

    @Test fun aeFpsRange_passesWhenRangeBuiltOnSeparateLineAndPassedByRef() {
        val ok = """
            val aeRange = android.util.Range(chosen.first, chosen.second)
            ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                aeRange,
            )
        """.trimIndent()
        assertNull(ruleAeFpsRangeCapabilityGated(svc(ok)))
    }

    @Test fun aeFpsRange_firesOnInlineRangeLiteral() {
        val bad = "ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(24, 30))"
        val msg = ruleAeFpsRangeCapabilityGated(svc(bad))
        assertNotNull(msg)
        assertTrue(msg!!.contains("ADR-0034"))
    }

    @Test fun aeFpsRange_ignoresAvailableRangesCharacteristicLine() {
        // the AVAILABLE list key must NOT trigger (different token), even though
        // the .map { it.lower to it.upper } line is nearby.
        val ok = "val a = info.getCameraCharacteristic(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)"
        assertNull(ruleAeFpsRangeCapabilityGated(svc(ok)))
    }

    @Test fun aeFpsRange_nullWhenServiceFileAbsent() {
        assertNull(ruleAeFpsRangeCapabilityGated(emptyList()))
    }
}
