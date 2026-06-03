package com.aritr.rova.ui.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Resolves a share-safe URI for a Rova recording across every export
 * tier. Prefers the canonical `MediaStore` content URI plumbed through
 * by the caller (Tier 1: from `manifest.pendingUri`; Tier 2/3: looked
 * up by `_DATA` against `MediaStore`); falls back to [FileProvider]
 * for legacy app-private artifacts whose path is covered by
 * `res/xml/file_paths.xml`.
 *
 * The `FileProvider` call is wrapped in
 * `try/catch IllegalArgumentException` because Phase 1.7 finalized
 * exports live under `Movies/Rova/...` — outside any declared
 * FileProvider root. A missing-or-not-yet-indexed `MediaStore` row
 * would otherwise crash the share entry point. Callers must treat
 * `null` as "not ready to share" and surface a non-crashing snackbar
 * or toast.
 *
 * Single source of truth shared by both [com.aritr.rova.ui.screens.HistoryScreen]
 * (selection-mode share button) and [com.aritr.rova.ui.PreviewActivity]
 * (single-video preview share button) so the two surfaces cannot
 * diverge on URI safety.
 */
internal fun safeShareUri(context: Context, file: File?, shareUri: Uri?): Uri? {
    // Prefer the canonical share URI (Tier 1 MediaStore, or B4c SAF
    // doc URI — both `content://`). SAF rows pass file == null, so the
    // FileProvider fallback below is guarded against a null File.
    shareUri?.let { return it }
    if (file == null) return null
    return try {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    } catch (_: IllegalArgumentException) {
        null
    }
}
