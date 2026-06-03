package com.aritr.rova.service.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * ADR-0024 §SAF route. Thin Android seam for the Storage Access
 * Framework publish step. Not unit-tested (mirrors Tier1AndroidOps);
 * SafExporter injects these as lambdas and is tested with fakes.
 *
 * Uses the DocumentFile API (not raw ContentResolver.query) so this seam
 * does not fall under the MediaStore-only checkExportPendingVisibilityOnQuery
 * discipline — it talks to a DocumentsProvider, not MediaStore. Every
 * function takes a Context (DocumentFile.fromTreeUri / fromSingleUri need it).
 *
 * The muxer never touches SAF — these helpers only run AFTER a local
 * private-temp MP4 already exists. The publish is a sequential byte copy
 * (openOutputStream), so non-seekable / cloud providers still work.
 */
internal object SafAndroidOps {

    /** True iff a persisted read+write grant for [treeUri] is still held. */
    fun isPersistedPermissionHeld(context: Context, treeUri: String): Boolean {
        val target = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == target && it.isWritePermission && it.isReadPermission
        }
    }

    /**
     * True iff the chosen folder is actually USABLE right now: the persisted
     * grant is still held AND the tree document still exists and is writable.
     *
     * The grant survives the user deleting the folder in a file manager, so a
     * grant-only check ([isPersistedPermissionHeld]) wrongly reports a deleted
     * folder as available — the session then freezes as SAF, records, and only
     * fails at export time with a misleading "Merge failed" (ADR-0024 §B4c
     * folder-gone fix). Probing `DocumentFile.canWrite()` catches folder-gone
     * up front, so the session falls back to the default tier at freeze (or
     * surfaces SAVE_FOLDER_UNAVAILABLE if the folder vanishes mid-session).
     * Best-effort: any provider throw is treated as "not usable".
     */
    fun isTargetWritable(context: Context, treeUri: String): Boolean = try {
        isPersistedPermissionHeld(context, treeUri) &&
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.canWrite() == true
    } catch (t: Throwable) {
        RovaLog.w("SafAndroidOps.isTargetWritable threw for $treeUri", t)
        false
    }

    /**
     * Create a child "video/mp4" document under [treeUri]. Returns the
     * created doc Uri string, or null if creation failed. May throw —
     * caller classifies the throwable (permission-held → transient, else
     * permanent). The provider MAY auto-rename; read the real name via
     * [displayNameOf].
     */
    fun createDocument(context: Context, treeUri: String, displayName: String): String? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        return tree.createFile("video/mp4", displayName)?.uri?.toString()
    }

    /** The provider-authoritative display name of [docUri], or null. */
    fun displayNameOf(context: Context, docUri: String): String? =
        DocumentFile.fromSingleUri(context, Uri.parse(docUri))?.name

    /** Sequential byte copy [src] → the SAF document [docUri]. May throw. */
    fun copyFileToDocument(context: Context, src: File, docUri: String) {
        context.contentResolver.openOutputStream(Uri.parse(docUri), "wt")?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("openOutputStream returned null for $docUri")
    }

    /** Valid iff the doc exists with non-zero length. Best-effort; false on throw. */
    fun validateDocument(context: Context, docUri: String): Boolean = try {
        val doc = DocumentFile.fromSingleUri(context, Uri.parse(docUri))
        doc != null && doc.exists() && doc.length() > 0L
    } catch (t: Throwable) {
        RovaLog.w("SafAndroidOps.validateDocument threw for $docUri", t)
        false
    }

    /** Best-effort delete of a partial/failed doc. Returns true on success. */
    fun deleteDocument(context: Context, docUri: String): Boolean = try {
        DocumentFile.fromSingleUri(context, Uri.parse(docUri))?.delete() ?: false
    } catch (t: Throwable) {
        RovaLog.w("SafAndroidOps.deleteDocument threw for $docUri", t)
        false
    }

    /**
     * Pick-time validation: create a tiny doc, write a byte, delete it.
     * Returns true iff the tree accepts our writes. Used by the Settings
     * picker to reject unusable providers before persisting the choice.
     */
    fun writeProbe(context: Context, treeUri: String): Boolean {
        var probeUri: String? = null
        return try {
            probeUri = createDocument(context, treeUri, "rova_probe_${System.nanoTime()}.tmp")
                ?: return false
            context.contentResolver.openOutputStream(Uri.parse(probeUri), "wt")?.use { it.write(byteArrayOf(0)) }
                ?: return false
            true
        } catch (t: Throwable) {
            RovaLog.w("SafAndroidOps.writeProbe failed for $treeUri", t)
            false
        } finally {
            probeUri?.let { deleteDocument(context, it) }
        }
    }
}
