package com.aritr.rova.data

/**
 * ADR-0029 §6 [Ratified A] — pure legacy-mode mapper. Legacy "Portrait" never
 * meant "portrait lock", only rotation-at-bind; Lock(ROTATION_0) preserves the
 * TYPICAL-case output (documented lossy assumption — no per-clip rotation
 * history was ever persisted). Migrated users keep least-surprise; NEW users
 * default to FollowDevice via RovaSettings defaults, not via this mapper.
 */
object ModeMigration {

    data class Migrated(
        val topology: String,
        val policy: String,
        val lockRotation: Int,
        val legacyMigrated: Boolean,
    )

    fun migrate(legacyMode: String?): Migrated = when (legacyMode) {
        "Portrait" -> Migrated("Single", "Lock", 0, legacyMigrated = true)
        "Landscape" -> Migrated("Single", "Lock", 1, legacyMigrated = true)
        "PortraitLandscape" -> Migrated("DualShot", "FollowDevice", -1, legacyMigrated = false)
        else -> Migrated("Single", "FollowDevice", -1, legacyMigrated = false)
    }
}
