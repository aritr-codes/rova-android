package com.aritr.rova.service.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.aritr.rova.utils.RovaLog
import com.aritr.rova.utils.validateMediaFromFd
import java.io.FileDescriptor
import java.io.IOException

/**
 * Phase 1.7 commit-4 — production wiring for [Tier1Exporter]'s injectable
 * seams. Every entry point is `@RequiresApi(Build.VERSION_CODES.Q)`
 * because:
 * - `MediaStore.Video.Media.IS_PENDING` is API 29+.
 * - `MediaStore.VOLUME_EXTERNAL_PRIMARY` is API 29+.
 * - The ADR 0003 §"FD Mode Amendment" `"rw"` rule applies only to Tier 1
 *   (Tier 2/3 use `MediaMuxer(String, ...)` and never open a pending FD).
 *
 * Runtime callers (commit 7 — `RovaRecordingService.performMerge`
 * replacement) reach this through [currentExportTier] dispatch, which
 * returns [com.aritr.rova.data.ExportTier.TIER1_API29_PLUS] only when
 * `Build.VERSION.SDK_INT >= Q`. The `@RequiresApi` annotations document
 * the static-typing half of that contract; the runtime tier dispatch
 * is the dynamic half.
 *
 * Internal to `service/export` per the Phase 1.7 commit-3 spec ("Any
 * shared helper must stay internal/private to service/export"). Tests
 * use the [Tier1Exporter] seams directly — this object is only invoked
 * by production wiring and is not exercised by JVM unit tests.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal object Tier1AndroidOps {

    private const val TAG = "Tier1AndroidOps"

    /** ADR 0003 §"FD Mode Amendment" — `"rw"` is the ONLY legal mode for Tier 1. */
    private const val FD_MODE_RW = "rw"

    /** Read-only mode for the recovery extractor probe — does not trigger seekability requirements. */
    private const val FD_MODE_R = "r"

    /**
     * Insert a `MediaStore.Video.Media` row with `IS_PENDING=1` into
     * `Movies/Rova/`. Returns the inserted URI as a string for the
     * manifest pointer, or `null` if the resolver returned `null`.
     */
    fun insertPendingRow(resolver: ContentResolver, displayName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Rova")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )?.toString()
    }

    /**
     * Open a writable FD on the pending row, run [block] against it, and
     * close on exit (success or throw). The mode is `"rw"` per ADR 0003
     * §"FD Mode Amendment" — `"w"` produces a non-seekable FD on Tier 1
     * and breaks `MediaMuxer.stop()`'s moov-atom rewrite. The
     * `checkPendingFdModeIsRW` lint guards source against the `"w"`
     * literal.
     */
    suspend fun withPendingFd(
        resolver: ContentResolver,
        uriString: String,
        mode: String,
        block: suspend (FileDescriptor) -> Unit
    ) {
        val uri = Uri.parse(uriString)
        val pfd = resolver.openFileDescriptor(uri, mode)
            ?: throw IOException("openFileDescriptor returned null for $uriString (mode=$mode)")
        try {
            block(pfd.fileDescriptor)
        } finally {
            try {
                pfd.close()
            } catch (t: Throwable) {
                RovaLog.w("$TAG.withPendingFd: PFD close threw for $uriString", t)
            }
        }
    }

    /**
     * Tier 1 publish atom — `IS_PENDING=0`. Returns the rich
     * [Tier1FinalizeResult] (NOT a boolean) so live and recovery can
     * dispatch differently on the `update`-returns-0 case (live treats
     * as failure; recovery after `validatePending=true` treats as
     * already-finalized — see the [Tier1FinalizeResult] KDoc for the
     * load-bearing rationale).
     */
    fun finalizePendingRow(resolver: ContentResolver, uriString: String): Tier1FinalizeResult {
        return try {
            val uri = Uri.parse(uriString)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            if (resolver.update(uri, values, null, null) > 0) {
                Tier1FinalizeResult.Finalized
            } else {
                Tier1FinalizeResult.NoRowsUpdated
            }
        } catch (t: Throwable) {
            Tier1FinalizeResult.Failed(t)
        }
    }

    /** Best-effort cleanup. Returns `true` if a row was removed. */
    fun deletePendingRow(resolver: ContentResolver, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return resolver.delete(uri, null, null) > 0
    }

    /**
     * Recovery probe — opens the pending row read-only and delegates to
     * [validateMediaFromFd] for full media-validity discipline (video
     * track present + readable sample). Track count alone is too weak
     * (per the commit-4 NO-GO patch); a corrupt MP4 with a populated
     * track table but no decodable samples must NOT be treated as
     * recoverable. Any throw or `null` PFD yields `false` so the caller
     * can route to abandon.
     */
    fun validatePending(resolver: ContentResolver, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            val pfd = resolver.openFileDescriptor(uri, FD_MODE_R) ?: return false
            try {
                validateMediaFromFd(pfd.fileDescriptor)
            } finally {
                try {
                    pfd.close()
                } catch (t: Throwable) {
                    RovaLog.w("$TAG.validatePending: PFD close threw for $uriString", t)
                }
            }
        } catch (t: Throwable) {
            RovaLog.w("$TAG.validatePending: probe failed for $uriString", t)
            false
        }
    }

    @Suppress("unused")
    private fun fdModeRwReference(): String = FD_MODE_RW
}
