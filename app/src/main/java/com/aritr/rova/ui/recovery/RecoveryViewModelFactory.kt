package com.aritr.rova.ui.recovery

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.RovaRecordingService

/**
 * Recovery subsystem (ADR-0005) factory for [RecoveryViewModel]. The recovery-keep terminal write
 * (`markTerminated(MULTI_SEGMENT_KEPT)`) is constructed here — its principled home — and is therefore
 * OUTSIDE the `ui/library/` + History/Library-screen scope of `checkLibraryNoManifestWrite` (ADR-0030 §2,
 * amended Slice 3). This is the only sanctioned manifest write reachable from the Library/History surface,
 * and it belongs to recovery, not Library metadata. Do NOT add favorite/rename/lastPlayedAt writes here —
 * those go through `LibraryMetadataStore`. `ui/recovery/` is not a general manifest-write location.
 *
 * `videosRoot == null` (storage unavailable at boot) disables load AND the two writes: the scan never ran,
 * there is nothing to load, and discard/markTerminated on a missing dir would be a no-op anyway — guarding
 * the lambdas avoids a hot-path NullPointer if the lazy `sessionStore` initializer throws.
 */
fun recoveryViewModelFactory(app: RovaApp, context: Context): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val sessionStoreAvailable = app.videosRoot != null
            val loadManifest: (String) -> SessionManifest? = if (sessionStoreAvailable) {
                { id -> app.sessionStore.loadManifest(id) }
            } else {
                { _ -> null }
            }
            val discardSession: suspend (String) -> Unit = if (sessionStoreAvailable) {
                { id -> app.sessionStore.discardSession(id) }
            } else {
                { _ -> }
            }
            val markKeptRaw: suspend (String) -> Unit = if (sessionStoreAvailable) {
                { id ->
                    // Recovery-subsystem terminal write (recovery-keep MULTI_SEGMENT_KEPT), owned by
                    // ADR-0005 — not Library favorite/rename metadata. Lives here (ui/recovery/), out of
                    // checkLibraryNoManifestWrite scope, per ADR-0030 §2 (amended Slice 3).
                    app.sessionStore.markTerminated(
                        sessionId = id,
                        terminated = Terminated.MULTI_SEGMENT_KEPT,
                        stopReason = StopReason.NONE,
                    )
                }
            } else {
                { _ -> }
            }
            val startRecoveryMergeFn: (String) -> Unit = { id ->
                RovaRecordingService.startRecoveryMerge(context, id)
            }
            RecoveryViewModel(
                recoveryReport = app.recoveryReport,
                loadManifest = loadManifest,
                discardSession = discardSession,
                markKeptRaw = markKeptRaw,
                startRecoveryMergeFn = startRecoveryMergeFn,
                mergeOutcome = app.recoveryMergeOutcomeSignal.state,
            )
        }
    }
