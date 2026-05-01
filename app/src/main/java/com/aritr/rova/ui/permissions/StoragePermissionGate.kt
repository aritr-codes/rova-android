package com.aritr.rova.ui.permissions

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phase 2 Slice 2.4 — pure decision for the Tier 2/3 storage-
 * permission banner.
 *
 * Recording is unblocked regardless of this gate; the banner only
 * announces that the public-gallery publish step (Tier 2/3:
 * `Environment.DIRECTORY_MOVIES/Rova/<name>.mp4` via `renameTo`) is
 * unavailable until `WRITE_EXTERNAL_STORAGE` is granted. On API 29+
 * the manifest's `maxSdkVersion="28"` makes WES a runtime no-op (Tier
 * 1 publishes via `MediaStore.IS_PENDING` and never asks), so the
 * banner is unconditionally hidden on Q and above.
 *
 * Design: the descriptor surface ([StorageBannerState], [evaluate])
 * is plain Kotlin so this gate is JVM-unit-testable without
 * Robolectric. [evaluateForCurrent] reads `Build.VERSION.SDK_INT` and
 * is the production entry point; [appDetailsSettingsIntent] is the
 * Android boundary and is compiled (not unit-tested) because the
 * project's `unitTests.isReturnDefaultValues = true` would silently
 * no-op `Intent` setters on the JVM and produce misleading green
 * tests. The wire-up layer (a future Slice 2.4-wire) is responsible
 * for `startActivity` and for the runtime-permission launcher flow.
 *
 * **Wire-up caution (Slice 2.4-wire's concern, encoded here so the
 * substrate caller knows the trap):** the natural shape "if
 * `shouldShowRequestPermissionRationale == false` then deep-link to
 * Settings" is **wrong**. The rationale flag also returns `false`
 * for *first-time* permission requests (the user has never seen the
 * dialog), not only for the permanently-denied state. The wire-up
 * slice must distinguish first-launch from permanently-denied via a
 * separate "have we asked at least once?" flag (typically a
 * `rememberSaveable` or a small SharedPreferences key) before falling
 * back to the App Details Settings intent built by
 * [appDetailsSettingsIntent]. This substrate intentionally exposes
 * only the action *label* ("Grant"), not the dispatch decision.
 */
internal object StoragePermissionGate {

    /**
     * Pure decision. Returns [StorageBannerState.Hidden] when:
     *  - `sdkInt >= Q` (Tier 1 / MediaStore — WES is a no-op), OR
     *  - `hasWriteExternalStorage` is true (Tier 2/3 ready).
     *
     * Returns [StorageBannerState.Shown] iff `sdkInt in 24..28` AND
     * the permission is missing — the only state where Tier 2/3
     * publish would silently fail at `renameTo` for lack of WES.
     */
    fun evaluate(
        sdkInt: Int,
        hasWriteExternalStorage: Boolean
    ): StorageBannerState = when {
        sdkInt >= Build.VERSION_CODES.Q -> StorageBannerState.Hidden
        hasWriteExternalStorage         -> StorageBannerState.Hidden
        else                            -> StorageBannerState.Shown(
            title       = BANNER_TITLE,
            body        = BANNER_BODY,
            actionLabel = BANNER_ACTION_LABEL
        )
    }

    /**
     * Production convenience: read [Build.VERSION.SDK_INT] from the
     * running build and delegate to [evaluate]. The permission state
     * is still caller-supplied — the caller's preferred check is
     * `ContextCompat.checkSelfPermission(...) == PERMISSION_GRANTED`,
     * which keeps this gate Context-free.
     */
    fun evaluateForCurrent(hasWriteExternalStorage: Boolean): StorageBannerState =
        evaluate(Build.VERSION.SDK_INT, hasWriteExternalStorage)

    /**
     * Build the launchable App Details Settings intent for the wire-
     * up slice's "Settings" fallback path. Boundary helper — not
     * exercised by JVM tests (Android stubs no-op).
     *
     * Mirrors [com.aritr.rova.ui.recovery.VendorGuidanceIntents]'s
     * fallback intent shape: same action, same `package:` URI scheme,
     * same `FLAG_ACTIVITY_NEW_TASK`. Reusing one canonical Intent
     * shape keeps the wire-up consistent across the recovery-card
     * vendor slot and the storage-permission banner.
     */
    fun appDetailsSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // Phase 2 Slice 2.4 — copy frozen here so tests can assert exact
    // strings without duplicating literals; the wire-up slice may
    // later promote these to strings.xml when i18n lands.
    internal const val BANNER_TITLE        = "Gallery export disabled"
    internal const val BANNER_BODY         = "Grant Storage permission to enable."
    internal const val BANNER_ACTION_LABEL = "Grant"
}

/**
 * State of the Tier 2/3 storage-permission banner. [Hidden] is a
 * singleton (no payload); [Shown] carries the user-visible copy +
 * action label. The wire-up slice maps [Shown] to the actual
 * Compose banner and supplies the action callback.
 */
sealed class StorageBannerState {
    object Hidden : StorageBannerState()
    data class Shown(
        val title: String,
        val body: String,
        val actionLabel: String
    ) : StorageBannerState()
}
