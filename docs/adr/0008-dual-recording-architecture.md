# ADR-0008 — Dual-recording architecture: CameraEffect + EGL fan-out

> Status: Accepted
> Date: 2026-05-14
> Slice: Phase 6.1a (foundation) + Phase 6.1b (UI enablement)
> Supersedes: none
> Related: ADR-0006 (recording lifecycle robustness); spec `docs/superpowers/specs/2026-05-14-phase-6.1a-dual-recording-foundation-design.md`

## Context

Phase 6 shipped a Mode picker with a P+L (Portrait + Landscape) tab as a disabled stencil. Activating P+L means producing two playable clips per session — a 9:16 portrait file and a 16:9 landscape file — from the same back or front camera, simultaneously.

CameraX 1.4.2's `VideoCapture` use case binds at most ONE video output per camera. `ConcurrentCamera` covers front+back simultaneous capture, NOT one camera with two outputs. `StreamSharing` fans out the internal pipeline but still produces a single `VideoCapture` recording. The CameraX path therefore cannot drive two simultaneous `VideoCapture` recordings out of one camera.

Three architectural paths were considered:

1. **Camera2 full rebuild.** Bypass CameraX. Open the camera via `CameraDevice`, register a `CameraCaptureSession` with up to 3 output Surfaces (preview + 2 encoder inputs). Highest control; loses CameraX lifecycle, lens-switch, FGS integration, AsyncCloser, and the recovery patterns. ~3-4 weeks engineering plus a parallel codepath to maintain forever.
2. **CameraX `CameraEffect` + EGL fan-out.** Bind a `CameraEffect` with target=PREVIEW. The effect's `SurfaceProcessor` receives the camera frames into an EGL `SurfaceTexture`, then a single GL context renders each frame to three target Surfaces: PreviewView's expected output + two `MediaCodec` encoder input surfaces (portrait, landscape). CameraX retains lifecycle/lens/FGS; the encoder layer is custom. ~1-2 weeks.
3. **Finalize-time crop.** Record one full-frame source clip per session; in the export pipeline, transcode-crop it into a portrait variant and a landscape variant. Simple. Loses pixels in the portrait crop — at FHD source (1920×1080) the portrait crop is 608×1080, below the 1080×1920 minimums of Instagram Reels / TikTok / Shorts. Forces UHD source to recover usable portrait quality, ~5× the storage and a thermal/battery hit. Locks Rova out of any future feature that needs an independent dual-encoder primitive (live composition, streaming, per-zone effects).

## Decision

Path 2: **`CameraEffect` with target=`CameraEffect.PREVIEW` + custom EGL fan-out + two MediaCodec encoders + two MediaMuxer outputs.**

- The dual pipeline lives in a new isolated package `com.aritr.rova.service.dualrecord` (public API at the root; impl in `internal/`).
- Phase 6.1a ships the package with zero runtime integration. Phase 6.1b binds it into `RovaRecordingService` when the user selects the P+L mode.
- Audio is captured once via `AudioRecord` + AAC encoder; encoded samples are broadcast to both muxers (D4).
- Rotation is signalled exclusively via `MediaFormat.KEY_ROTATION`; the GL shader applies crop + scale only (no rotation). Prevents double-rotation (180° / sideways playback).
- Output files use a paired suffix in the same session directory: `segment_NNNN_P.mp4` + `segment_NNNN_L.mp4` (D6).
- `DualMuxer` has a state machine with tolerant per-side failure — one muxer failing does not poison the other.

## Consequences

**Positive:**
- Each output file is independently usable at its native resolution. Portrait clip = real 1080×1920 (FHD configuration) — meets all current social-platform minimums.
- CameraX lifecycle / lens-switch / FGS / recovery patterns preserved. Only the encoder layer is custom.
- Foundation for future features: live split-screen composition, streaming, per-zone effects, per-output bitrate ladders.
- Phase 6.1a is invisible — zero risk to the existing Portrait-only / Landscape-only flow, since `RovaRecordingService` is not touched.

**Negative:**
- Custom EGL + `MediaCodec` runtime cannot be unit-tested at the JVM level. Verification deferred to 6.1b on-device smoke (matches the existing `VideoMerger` precedent — real `MediaMuxer`, zero unit tests).
- 2× video encoder runtime cost vs single-mode recording — expected to fit within thermal envelope at FHD source on a baseline device but is a 6.1b smoke-test concern.
- 2× storage budget per segment — `StorageEstimator.estimatePeakBytes` needs a P+L-aware multiplier in 6.1b (out of scope for 6.1a).
- A new pipeline-foundation package adds surface area; the impl/internal split + KDoc + the ADR mitigate the long-term maintenance cost.

## Notes

- Phase 6.1b owns: `SessionConfig.mode` value `"P + L"`, `RovaSettings.mode` coercion, manifest schema bump (if needed), `RecoveryScanner.SEGMENT_REGEX` extension, `StorageEstimator.estimatePeakBytes` extension, `ModeTabsPicker` tab activation, split-zone preview layout, service-level integration, on-device smoke verification.
- The Phase 4.1+4.1b precedence-VM / Start-gate invariant is preserved byte-for-byte in 6.1a (zero diff to `WarningId`, `WarningPrecedence`, `RecordScreen.kt:107-122`).
