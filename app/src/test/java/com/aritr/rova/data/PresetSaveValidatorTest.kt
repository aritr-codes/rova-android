package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetSaveValidatorTest {

    private fun custom(name: String) =
        RovaPreset(name = name, duration = 15, interval = 3, loopCount = 5, resolution = QualityPresets.FHD, id = "custom.x")

    @Test fun freshNameIsOk() {
        assertEquals(PresetSaveValidator.Result.Ok, PresetSaveValidator.validateName("Beach", emptyList()))
    }

    @Test fun blankIsBlank() {
        assertEquals(PresetSaveValidator.Result.Blank, PresetSaveValidator.validateName("", emptyList()))
    }

    @Test fun whitespaceOnlyIsBlank() {
        assertEquals(PresetSaveValidator.Result.Blank, PresetSaveValidator.validateName("   ", emptyList()))
    }

    @Test fun overMaxIsTooLong() {
        val name = "x".repeat(PresetSaveValidator.MAX_NAME_LENGTH + 1)
        assertEquals(PresetSaveValidator.Result.TooLong, PresetSaveValidator.validateName(name, emptyList()))
    }

    @Test fun atMaxIsOk() {
        val name = "x".repeat(PresetSaveValidator.MAX_NAME_LENGTH)
        assertEquals(PresetSaveValidator.Result.Ok, PresetSaveValidator.validateName(name, emptyList()))
    }

    @Test fun duplicateCustomNameCaseInsensitive() {
        assertEquals(
            PresetSaveValidator.Result.DuplicateName,
            PresetSaveValidator.validateName("night", listOf(custom("Night"))),
        )
    }

    @Test fun builtInNameIsReserved() {
        assertEquals(
            PresetSaveValidator.Result.DuplicateName,
            PresetSaveValidator.validateName("Standard", emptyList()),
        )
    }

    @Test fun trimsBeforeChecking() {
        assertEquals(
            PresetSaveValidator.Result.DuplicateName,
            PresetSaveValidator.validateName("  Night  ", listOf(custom("Night"))),
        )
    }
}
