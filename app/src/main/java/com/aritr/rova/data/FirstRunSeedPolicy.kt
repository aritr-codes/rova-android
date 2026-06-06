package com.aritr.rova.data

/**
 * ADR-0026 — decides whether to seed the Standard preset's values on launch.
 *
 * We only seed on a PROVEN fresh install: never-seeded AND no recording pref has
 * ever been written. An existing user who simply never tweaked a stepper still
 * has `anyRecordingPrefPresent == false` only on a truly clean install, because
 * the seed itself writes those keys. This guarantees we never overwrite a config
 * a user already established (codex review #1).
 */
object FirstRunSeedPolicy {
    fun shouldSeed(presetSeeded: Boolean, anyRecordingPrefPresent: Boolean): Boolean =
        !presetSeeded && !anyRecordingPrefPresent
}
