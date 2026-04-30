package com.aritr.rova.service.surface

import androidx.camera.core.Preview

/**
 * Headless `Preview.SurfaceProvider` for CameraX when no UI is in the foreground.
 *
 * Two API-gated implementations exist (see ADR 0002):
 *   - API 24-25: `ImageReaderHeadlessSurface` (`ImageReader.PRIVATE`).
 *   - API 26+:   `SurfaceTextureHeadlessSurface` (`SurfaceTexture(false)`).
 *
 * Use [HeadlessPreviewSurfaces.create] to obtain the right impl for the device.
 *
 * Each `SurfaceRequest` triggers a fresh per-request consumer; the consumer's
 * lifecycle is bound to the request's `provideSurface` result callback.
 * No resources cross requests; no resources are released outside the callback.
 */
interface HeadlessPreviewSurface {

    val provider: Preview.SurfaceProvider

    /**
     * Marks this provider closed. Subsequent `SurfaceRequest`s receive
     * `willNotProvideSurface()`. Per-request resources already in flight are
     * released by their own provideSurface callback when CameraX finishes
     * with them — no eager release.
     */
    fun close()

    val isClosed: Boolean
}
