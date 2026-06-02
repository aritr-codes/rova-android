package com.aritr.rova.service.export

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import java.io.File

/**
 * Phase 6.1b T19 final-review remediation — pure-JVM helpers for the
 * late-terminal reconciliation pass's `validateTierArtifact` seam.
 *
 * Extracted from the inline lambda in [com.aritr.rova.RovaApp.buildExportRecoveryRunner]
 * so the per-side P+L branch is unit-testable without Robolectric or a
 * real `ContentResolver`. Production wiring threads through:
 *
 *  1. `Tier1AndroidOps.validatePending(resolver, uri)` for the Tier 1
 *     URI probe (single-mode + P+L) — the `tier1Probe` callback.
 *  2. `File(path).exists() && length > 0` for Tier 2/3 — provided by
 *     this helper inline.
 *
 * Validity rules (mirrors single-mode pre-T19 behavior):
 *  - **Tier 1** — probe each candidate URI; valid iff `tier1Probe(uri)`
 *    returns `true`. Null URI → not-valid. Below the Tier 1 SDK floor
 *    (caller's responsibility to gate via `Build.VERSION.SDK_INT`) the
 *    caller passes `tier1Probe = { false }` and the result is invalid.
 *  - **Tier 2/3** — file at the candidate path exists with non-zero
 *    length. Null path → not-valid.
 *
 * Per-side dispatch (P+L):
 *  - `config.mode == "PortraitLandscape"` → consult per-side pointers.
 *  - At-least-one-side-valid satisfies the gate (mirrors the
 *    T13 D12 independent-atomicity contract; if one side finalized
 *    cleanly, the user has a playable artifact and the manifest
 *    deserves `terminated = COMPLETED`).
 *  - Both sides null/invalid → invalid.
 *
 * Single-mode (legacy / `"Portrait"` / `"Landscape"`) reads the shared
 * `pendingUri` / `publicTargetPath` exactly as before — byte-identical
 * to pre-T19 behavior.
 */
internal object TierArtifactValidator {

    /**
     * Returns `true` iff this manifest's claimed artifact is intact.
     *
     * @param manifest the session manifest under reconciliation
     * @param tier1Probe `(uriString) -> Boolean` — callback that probes
     *   a Tier 1 `MediaStore` URI for readability (production wires
     *   this to `Tier1AndroidOps.validatePending`). Below the Tier 1
     *   SDK floor, callers pass a constant `false` callback.
     */
    fun isArtifactValid(
        manifest: SessionManifest,
        // ADR-0024 — probes a SAF document URI for existence + non-zero length
        // (production wires this to SafAndroidOps.validateDocument). Defaults
        // to a constant `false` so every pre-B4 caller stays byte-identical.
        //
        // Placed BEFORE tier1Probe (despite being the new param) so that
        // tier1Probe remains the LAST parameter: the existing call sites use
        // trailing-lambda syntax `isArtifactValid(m) { uri -> ... }` which
        // binds to the last param. Keeping tier1Probe last preserves every
        // pre-B4 caller verbatim; new callers pass both by name.
        safProbe: (uriString: String) -> Boolean = { false },
        tier1Probe: (uriString: String) -> Boolean
    ): Boolean {
        // P+L branch — at-least-one-side-valid satisfies the gate. The
        // shared pointers are null for a P+L session per OQ-C; we must
        // consult the per-side fields instead.
        if (manifest.config.mode == "PortraitLandscape") {
            return when (manifest.exportTier) {
                ExportTier.TIER1_API29_PLUS -> {
                    val portraitOk = manifest.portraitPendingUri?.let(tier1Probe) ?: false
                    val landscapeOk = manifest.landscapePendingUri?.let(tier1Probe) ?: false
                    portraitOk || landscapeOk
                }
                ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> {
                    val portraitOk = isFileArtifactValid(manifest.portraitPublicTargetPath)
                    val landscapeOk = isFileArtifactValid(manifest.landscapePublicTargetPath)
                    portraitOk || landscapeOk
                }
                ExportTier.SAF_DESTINATION -> {
                    val p = manifest.portraitSafTargetDocUri?.let(safProbe) ?: false
                    val l = manifest.landscapeSafTargetDocUri?.let(safProbe) ?: false
                    p || l
                }
            }
        }
        // Single-mode — byte-identical to pre-T19 behavior.
        return when (manifest.exportTier) {
            ExportTier.TIER1_API29_PLUS -> manifest.pendingUri?.let(tier1Probe) ?: false
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 ->
                isFileArtifactValid(manifest.publicTargetPath)
            ExportTier.SAF_DESTINATION -> manifest.safTargetDocUri?.let(safProbe) ?: false
        }
    }

    private fun isFileArtifactValid(path: String?): Boolean {
        if (path == null) return false
        val f = File(path)
        return f.exists() && f.length() > 0L
    }
}
