package com.aritr.rova.data

/**
 * ADR-0029 Decision 1 — capture-topology axis. `Single` replaces BOTH legacy
 * "Portrait" and "Landscape" (they differed only in orientation, never in
 * topology). Persisted form is the enum's [persisted] string; orientation is
 * the separate OrientationPolicy axis (never conflated here).
 */
enum class CaptureTopology(val persisted: String) {
    Single("Single"),
    DualShot("DualShot"),
    FrontBack("FrontBack");

    companion object {
        fun isValidPersisted(value: String): Boolean = entries.any { it.persisted == value }
        fun fromPersisted(value: String?): CaptureTopology =
            entries.firstOrNull { it.persisted == value } ?: Single
    }
}
