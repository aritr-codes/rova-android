# ADR 0002 — Headless Camera Surface (API-Gated)

- **Status:** Accepted
- **Date:** 2026-04-25
- **Phase:** 0 (pre-implementation prerequisite)
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ROADMAP_v6.md §1.4 (Recording Lifecycle Robustness), risk **C13**

---

## Context

CameraX `Preview` always requires a `SurfaceProvider`. When the UI is not in the foreground (screen off, app backgrounded, notification-only state), Rova's foreground service must still satisfy the preview pipeline — otherwise CameraX refuses to bind and the recording fails to start.

The original code used `SurfaceTexture(0)` to fabricate a sink. This is wrong on two counts:

1. The `SurfaceTexture(int texName)` constructor expects an **attached** GL texture name; passing `0` is unspecified behavior — works on some drivers, throws on others.
2. The `SurfaceTexture(boolean singleBufferMode)` constructor was added at **API 26**; calling it on API 24–25 is a `NoSuchMethodError` at runtime.

`minSdk = 24` forces a split surface strategy. The headless path on 24–25 cannot use `SurfaceTexture(boolean)`; it must use a different consumer entirely.

## Decision

Two API-gated paths share a common interface (`HeadlessPreviewSurface`) consumed by the recording service. Selection is by `Build.VERSION.SDK_INT` at construction time.

### API 24–25 — `ImageReader.PRIVATE`

- Construct: `ImageReader.newInstance(width, height, ImageFormat.PRIVATE, /* maxImages */ 2)`.
- In `Preview.SurfaceProvider.onSurfaceRequested(request)`:
  - `request.provideSurface(imageReader.surface, executor) { result -> imageReader.close() }`.
- Drain in `OnImageAvailableListener`: `acquireLatestImage()?.close()` — never let the queue back up.
- **Lifecycle rule:** `imageReader.close()` is invoked **only** from inside the `provideSurface` result callback. Do not close on cancellation paths, do not close from the recording service's `onDestroy` directly. The result callback is CameraX's contract for "I am done with this surface."

### API 26+ — `SurfaceTexture(false)`

- Construct: `SurfaceTexture(/* singleBufferMode */ false)` — already detached; **do not** call `detachFromGLContext()`. The constructor flag returns a texture that has no GL context bound, which is what we need.
- `setDefaultBufferSize(width, height)` to match the resolution.
- Wrap: `Surface(texture)`.
- In `Preview.SurfaceProvider.onSurfaceRequested(request)`:
  - `request.provideSurface(surface, executor) { result -> surface.release(); texture.release() }`.
- **Lifecycle rule:** both releases are gated by the result callback only.

### Common interface (shape, not implementation)

```
interface HeadlessPreviewSurface {
    val provider: Preview.SurfaceProvider
    fun close()        // idempotent; deletes any held native resources
    val isClosed: Boolean
}
```

A factory selects the implementation:

```
fun create(width: Int, height: Int, executor: Executor): HeadlessPreviewSurface =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        SurfaceTextureHeadlessSurface(width, height, executor)
    else
        ImageReaderHeadlessSurface(width, height, executor)
```

Both implementations are skeletons in Phase 0 (no behavior wired into the service yet); the service consumes them in Phase 1.4 (C13 implementation).

## Consequences

### Positive

- One call site in the service (`HeadlessPreviewSurface.provider`) regardless of API. Surface lifecycle complexity is contained in two small classes.
- The "close only from result callback" rule is captured at the type-system level (the implementation never exposes the underlying `ImageReader` / `Surface` directly; closure is internal).
- Lint rule **No `SurfaceTexture#detachFromGLContext()` anywhere** (introduced v4) is enforceable because no production code path calls it.

### Negative

- Two implementations to maintain. Acceptable: each is small (≈30 LOC) and maps to a documented platform contract.
- API 24–25 path requires draining frames in the listener even though no consumer reads them. This burns minimal CPU; the alternative (no listener) leaks `Image` objects.

### Neutral

- The factory does not test for low-level capabilities (e.g. driver `ImageFormat.PRIVATE` support); `PRIVATE` is mandatory in the platform spec from API 23 onward, so the assumption is safe at `minSdk 24`.

## Acceptance Criteria

- ADR file present at `docs/adr/0002-headless-surface.md`.
- Skeleton classes `HeadlessPreviewSurface` (interface), `ImageReaderHeadlessSurface` (24–25 impl), `SurfaceTextureHeadlessSurface` (26+ impl) committed to repo with no behavior wired into the recording service.
- Existing `SurfaceTexture(0)` usage in `RovaRecordingService.kt` flagged for replacement in Phase 1.4 (C13). Not replaced in Phase 0 — replacement is a behavior change and belongs in Phase 1.

## Lint Rules (from this ADR)

- **No `SurfaceTexture#detachFromGLContext()` anywhere.** (v4 — retained.)
- `SurfaceTexture(boolean)` constructor must be guarded by `Build.VERSION.SDK_INT >= 26`.
- `ImageReader` headless-preview consumers must close from the `provideSurface` result callback only — CI grep enforces.

## References

- ROADMAP_v6.md §1.4 (Recording Lifecycle Robustness — unchanged from v5/v4).
- ROADMAP_v4.md §1.4 (correction note: `SurfaceTexture(false)` already detached).
- Risk **C13** in the risk register.
- Android docs: `Preview.SurfaceProvider`, `ImageReader.PRIVATE`, `SurfaceTexture(boolean)`.
