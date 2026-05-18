# ADR-0010: Canonical UV Frame + First-Principles Render Pipeline

**Status:** Accepted
**Date:** 2026-05-18
**Supersedes:** (none)
**Related:** ADR-0009 (DualShot 4:3 source aspect)

## Context

Phase 6.1c (PR #24, merged @ `ba252c3`) shipped the DualShot render pipeline with a 3-round on-device smoke-fix series that empirically tuned a `+270°` per-side UV correction. The correction was identical for both PORTRAIT and LANDSCAPE sides — a structural tell that it was compensating for an upstream concern (likely OES sensor's native orientation) rather than a per-side need.

PR #25 (ADR-0009, branch `feat/dualshot-4-3-source-aspect`) then fixed the source aspect (forced-16:9 → native-4:3), resolving the PORTRAIT-zone 1.78× vertical stretch. Owner's 2026-05-18 review then asked for an architectural pass to validate the render pipeline before merging PR #25.

The audit confirmed the renderer is structurally independent per target (single shared OES texture, independent cropMatrix/viewport/encoderSurface per side, no Portrait→Landscape derivation chain). But the audit ALSO surfaced:

1. Hidden coupling: identical `+270°` sideCorrection on both sides smelled of an upstream normalization issue masked by per-side correction.
2. Smoke-fix sediment: math was empirically tuned, not first-principles derived — next sensor/device may surface a new failure mode.
3. No validation harness: no cross-side independence asserts, no semantic-space tests.
4. Architectural drift risk: `RenderTarget.cropMatrix` field name implied crop-only semantics but actually carried the full composed transform.

## Decision

**Introduce a canonical UV frame as the foundational invariant of the DualShot render pipeline.** All transforms downstream of `textureNormalization` operate in this frame:

- `+U = screen-right`
- `+V = screen-down`
- origin = top-left
- rear camera unmirrored
- device-natural orientation aligned (portrait-up for typical phones)

**Re-derive the UV pipeline from first principles** as the composition:

```
uTexMatrix = sideAspectCrop × displayRotationCorrection × textureNormalization × texMatrix × mirrorMatrix
```

Where:
- `mirrorMatrix` — user-facing semantic correction (preview-only front-camera mirror); NOT orientation normalization
- `texMatrix` — SurfaceTexture's intrinsic OES correction (opaque, frame-local)
- `textureNormalization` — NEW; maps effective OES UV basis → canonical UV frame; sourced from `CameraCharacteristics.SENSOR_ORIENTATION`
- `displayRotationCorrection` — device-tilt compensation
- `sideAspectCrop` — terminal transform in canonical UV space; per-side center-crop

**Hybrid coexistence model.** New helpers (`buildTextureNormalization`, `buildUvTransformV2`) ship as parallel dead-code in `AspectFitMath.kt`. Legacy `buildCropMatrix` is `@Deprecated` but remains the default runtime path. Activation requires `BuildConfig.DEBUG` + `SharedPreferences.pref.dev.useFirstPrinciplesRender=true`.

**Migration policy.** After V2 becomes default (a future migration PR, 1–2 release cycles after sub-project 1 lands) AND sub-project 2's multi-config smoke validates the V2 path across (back/front camera × rotated device × fallback HW path): downgrade `AspectFitMathBridgeTest` to `@Ignore`'d historical reference, then delete with legacy `buildCropMatrix` deprecation in one atomic PR.

**Architectural non-goal.** No device-specific calibration tables or empirical correction constants. If future OEM behavior reveals divergence, the response is to refine the derivation (e.g. fold a `texMatrix`-convention adjustment into `buildTextureNormalization`'s contract) — NOT to add per-device constants.

## Consequences

**Positive:**
- Renderer has formal coordinate-system semantics, not historically accumulated transform behavior.
- Future contributors can reason about the pipeline from documented invariants instead of smoke-fix archaeology.
- The `+270°` empirical sideCorrection is eliminated in V2 — replaced by `sensorOrientation`-derived `textureNormalization`.
- Bridge test gates V2 against legacy regression during the hybrid coexistence window.
- Aspect-ratio preservation invariant tests give first-class coverage to the original bug class.
- Debug snapshot plumbing (`DualShotMatrixDebugInfo` + `EglRouter.debugSnapshot`) ready for sub-project 2's overlay.

**Negative / accepted tradeoffs:**
- Maintenance overhead during hybrid period: legacy + V2 coexist in `AspectFitMath.kt` until migration PR.
- Bridge test passes only for `sensorOrientation = 270` (the value at which legacy's empirical `+270°` matches V2's principled derivation). Other sensorOrientation values are intentional V2 corrections, not bridge-gated — relying on sub-project 2's multi-config smoke for validation.
- 4 scratch buffer fields on `EglRouter` instance (~256 bytes) — negligible.
- Debug snapshot writes add ~280 B/frame/side when enabled — negligible vs encoder bandwidth.

## Alternatives Rejected

**(a) Calibration table (`(lensFacing, sensorOrientation, displayRotation) → matrix`).** Defers the "why" question indefinitely. Calibration table grows linearly with device coverage. Rejected as the architectural anti-pattern this ADR exists to prevent.

**(b) Fix `buildDisplayRotationCorrection` sign convention.** Treats `+270°` as evidence of a sign error. Doesn't explain why BOTH sides need identical correction. Would just shift the bug elsewhere.

**(c) Same-PR migration (flip default to V2 + delete legacy in one PR).** Replaces the visual trust-anchor (legacy math validated by PR #25's smoke) without an opportunity for on-device A/B comparison. Hybrid pattern lets V2 earn its trust over time.

**(d) FBO indirection (explicit per-target framebuffer objects).** Adds a copy step + memory overhead. Direct-to-encoder works. YAGNI for current pipeline.

## References

- Spec: `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` (committed @ `4e55191`)
- Plan: `docs/superpowers/plans/2026-05-18-render-architecture-audit.md`
- ADR-0009: `docs/adr/0009-dualshot-4-3-source-aspect.md` (predecessor — 4:3 source aspect)
- Phase 6.1c memory: 3-round smoke-fix series that produced the empirical `+270°` sideCorrection this ADR eliminates
- Camera2 docs: `CameraCharacteristics.SENSOR_ORIENTATION` (guaranteed multiple-of-90)
