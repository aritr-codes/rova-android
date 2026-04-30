package com.aritr.rova.service.surface

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.camera.core.Preview
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 26+ headless preview surface (see ADR 0002).
 *
 * Per `SurfaceRequest`: a fresh `SurfaceTexture(false)` (already detached;
 * `detachFromGLContext()` is forbidden) is sized to the request's resolution,
 * wrapped in a fresh `Surface`, and **both** are released only from inside the
 * request's `provideSurface` result callback.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class SurfaceTextureHeadlessSurface(
    private val executor: Executor
) : HeadlessPreviewSurface {

    private val closed = AtomicBoolean(false)

    override val provider: Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        if (closed.get()) {
            request.willNotProvideSurface()
            return@SurfaceProvider
        }

        val texture = SurfaceTexture(false).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val surface = Surface(texture)

        request.provideSurface(surface, executor) { _ ->
            try { surface.release() } catch (_: Exception) {}
            try { texture.release() } catch (_: Exception) {}
        }
    }

    override fun close() {
        closed.set(true)
    }

    override val isClosed: Boolean
        get() = closed.get()
}
