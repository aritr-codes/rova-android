package com.aritr.rova.ui.screens

/**
 * B5 / ADR-0025 — visibility policy for the History *legacy* file-system scan.
 *
 * `HistoryViewModel.legacyFileSystemScan` is a pre-Phase-1.7 upgrade fallback:
 * it surfaces `Rova_*.mp4` files left under `videos/<sessionId>/` by old builds
 * that never wrote a `manifest.json`. It is deliberately vaultState-blind, which
 * is exactly why it must not look inside manifest-backed session dirs.
 *
 * A session dir that HAS a manifest is owned by the manifest-driven path, which
 * applies [com.aritr.rova.ui.vault.isLibraryVisible] (PUBLIC only). The legacy
 * scan therefore skips any manifest-backed dir.
 *
 * Gating on manifest *presence* (not successful load) is intentional and
 * **fail-closed**: the private vault now merges to a plain `Rova_*.mp4` (not the
 * old `*.mp4.private`), so a vaulted recording would otherwise leak into the
 * Library — and would still leak under a narrower "exclude only manifests that
 * parse as non-PUBLIC" rule if the manifest were corrupt/unparseable. Keying off
 * the manifest file existing on disk closes that hole regardless of parse state.
 * (Smoke round 1, 2026-06-04 — confirmed on-device: the vault file appeared in
 * the Library and played via the FLAG_SECURE-less PreviewActivity.)
 */
fun legacyScanIncludesSessionDir(hasManifest: Boolean): Boolean = !hasManifest
