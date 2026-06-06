package com.aritr.rova.data

/**
 * ADR-0026 — validates a proposed custom-preset name. Pure so it can drive both
 * the live naming-dialog error state and the [RecordViewModel.savePreset]
 * defensive guard. Built-in names are reserved (a custom may not shadow
 * "Standard" etc.); duplicate detection is case-insensitive and trim-tolerant.
 */
object PresetSaveValidator {
    const val MAX_NAME_LENGTH = 40

    sealed interface Result {
        data object Ok : Result
        data object Blank : Result
        data object TooLong : Result
        data object DuplicateName : Result
    }

    /** [existing] = current custom presets. Built-in names are also reserved. */
    fun validateName(rawName: String, existing: List<RovaPreset>): Result {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.Blank
        if (name.length > MAX_NAME_LENGTH) return Result.TooLong
        val taken = (BuiltInPresets.all + existing).any { it.name.equals(name, ignoreCase = true) }
        if (taken) return Result.DuplicateName
        return Result.Ok
    }
}
