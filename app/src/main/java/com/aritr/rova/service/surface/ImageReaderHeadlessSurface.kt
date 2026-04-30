package com.aritr.rova.service.surface

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.Preview
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 24-25 headless preview surface (see ADR 0002).
 *
 * Per `SurfaceRequest`: a fresh `ImageReader.PRIVATE` (2-buffer) is built using
 * the request's resolution, registered with a dedicated `HandlerThread` so the
 * `OnImageAvailableListener` never depends on the calling thread's looper, and
 * released **only** from inside the request's `provideSurface` result callback.
 *
 * `closed` ↔ `outstanding` updates are guarded by a single intrinsic lock so a
 * concurrent `close()` cannot quit the handler thread between a request's
 * closed-check and its insertion into the outstanding set.
 */
internal class ImageReaderHeadlessSurface(
    private val executor: Executor
) : HeadlessPreviewSurface {

    private val handlerThread: HandlerThread = HandlerThread("rova-imagereader").apply { start() }
    private val handler: Handler = Handler(handlerThread.looper)

    private val lock = Any()
    private val outstanding: MutableSet<ImageReader> =
        Collections.synchronizedSet(mutableSetOf())
    private val closed = AtomicBoolean(false)

    override val provider: Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        val width = request.resolution.width
        val height = request.resolution.height

        // Atomic: decide closed-ness and (if open) reserve a slot in outstanding
        // before any concurrent close() can run maybeQuitHandlerLocked.
        val reader = synchronized(lock) {
            if (closed.get()) {
                request.willNotProvideSurface()
                return@SurfaceProvider
            }
            ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 2).also {
                outstanding.add(it)
            }
        }

        // Listener registration after the lock is safe: the reader is already
        // in outstanding, so close() observes non-empty and won't quit the
        // handler thread.
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
        }, handler)

        request.provideSurface(reader.surface, executor) { _ ->
            try { reader.close() } catch (_: Exception) {}
            synchronized(lock) {
                outstanding.remove(reader)
                maybeQuitHandlerLocked()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed.compareAndSet(false, true)) {
                maybeQuitHandlerLocked()
            }
        }
    }

    override val isClosed: Boolean
        get() = closed.get()

    private fun maybeQuitHandlerLocked() {
        if (closed.get() && outstanding.isEmpty()) {
            try { handlerThread.quitSafely() } catch (_: Exception) {}
        }
    }
}
