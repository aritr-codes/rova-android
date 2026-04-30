package com.aritr.rova.service.export

import android.content.Context
import android.media.MediaScannerConnection
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Phase 1.7 commit-2 (Patch 2 — bounded scanFile wait).
 *
 * Wraps `MediaScannerConnection.scanFile` in a bounded suspend
 * function. The scanFile callback is fire-and-forget at the platform
 * layer — a misbehaving `MediaScanner` can swallow it. Holding the
 * foreground service open waiting for the callback would block the
 * user's ability to start a new session and burn wakelock budget.
 *
 * The 5 s default ceiling lets the gallery-index land under normal
 * load and degrades gracefully on stuck devices: the exporter writes
 * `exportState = FINALIZED, mediaScanCompleted = false`, retains
 * `privateTempPath`, and Phase 1.7 cold-launch recovery's deferred-scan
 * branch re-fires `scanFile` on the next launch (idempotent).
 *
 * Lint partner: `checkScanFileBoundedWait` (in `app/build.gradle.kts`)
 * forbids any `MediaScannerConnection.scanFile` call site outside this
 * file — every caller goes through [MediaScanWaiter.scanAndWait].
 */
interface MediaScanWaiter {

    /**
     * Fire `MediaScannerConnection.scanFile` on [file] and await the
     * `onScanCompleted` callback for up to [timeoutMillis].
     *
     * @return `true` if the callback fired within budget; `false` on
     *   timeout. `false` does NOT mean the scan failed at the platform
     *   level — only that the callback didn't return in time. The
     *   underlying scan may still complete asynchronously; recovery
     *   re-fires the call on the next cold launch (idempotent at the
     *   `MediaStore` layer).
     */
    suspend fun scanAndWait(
        file: File,
        mimeType: String = DEFAULT_MIME_TYPE,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): Boolean

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_MIME_TYPE = "video/mp4"
    }
}

/**
 * Production [MediaScanWaiter] backed by `MediaScannerConnection.scanFile`.
 *
 * Cancellation: `withTimeoutOrNull` cancels the body on timeout. The
 * platform `scanFile` API has no cancel hook, so the underlying scan
 * may still complete and invoke our callback after the coroutine has
 * been cancelled — `cont.resume(...)` on an inactive continuation is a
 * no-op (per kotlinx-coroutines contract), so we don't leak.
 */
class AndroidMediaScanWaiter(private val context: Context) : MediaScanWaiter {

    override suspend fun scanAndWait(
        file: File,
        mimeType: String,
        timeoutMillis: Long
    ): Boolean {
        val result = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Boolean> { cont ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType)
                ) { _, _ ->
                    if (cont.isActive) cont.resume(true)
                }
            }
        }
        if (result == null) {
            RovaLog.w(
                "MediaScanWaiter: scanFile timed out after ${timeoutMillis}ms for ${file.absolutePath}"
            )
            return false
        }
        return result
    }
}
