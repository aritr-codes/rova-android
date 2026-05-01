package com.aritr.rova.ui.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phase 2 Slice 2.2a — pure vendor-guidance candidate selector + thin
 * Intent-builder boundary.
 *
 * The 2.1b recovery card vendor slot calls [resolveForCurrent] to get
 * a launchable [Intent] that opens the vendor-specific auto-start /
 * battery-optimization screen, with a graceful fallback to the app's
 * own settings page.
 *
 * Design: the descriptor surface ([Candidate], [VendorComponentRef])
 * is plain Kotlin so [selectCandidate] is JVM-unit-testable without
 * Robolectric. [toIntent] and [resolveForCurrent] are the Android
 * boundary; they are not exercised by JVM tests but are compiled by
 * `assembleDebug`. The 2.1b layer is responsible for `startActivity`.
 *
 * Vendor candidate component names are sourced from public OEM
 * reverse-engineering and drift across firmware versions; the
 * `canResolveVendor` gate (production: `pm.resolveActivity`) is the
 * safety net against stale or removed components.
 */
object VendorGuidanceIntents {

    /** Plain (Android-free) reference to a vendor Activity component. */
    data class VendorComponentRef(
        val packageName: String,
        val className: String
    )

    /** What [selectCandidate] picked, before Intent construction. */
    sealed class Candidate {
        data class VendorComponent(
            val ref: VendorComponentRef,
            val vendorLabel: String
        ) : Candidate()

        data class AppDetailsSettings(
            val packageName: String
        ) : Candidate()
    }

    /**
     * Pick the best resolvable candidate for the given device + app.
     *
     * Iteration: vendor buckets (per [candidatesFor]) in declared order;
     * within a bucket, components in declared order. First component
     * whose [canResolveVendor] returns `true` wins. If no vendor
     * candidate resolves, falls back to [Candidate.AppDetailsSettings]
     * gated by [canResolveAppSettings]; if that also fails, returns
     * `null`.
     *
     * @param manufacturer typically `Build.MANUFACTURER`. Case-insensitive.
     * @param appId application package id (e.g. `com.aritr.rova`).
     * @param canResolveVendor receives a candidate ref; production passes
     *   a closure over `pm.resolveActivity`. Tests pass a fake set lookup.
     * @param canResolveAppSettings receives the app id; production passes
     *   `pm.resolveActivity` against the ACTION_APPLICATION_DETAILS_SETTINGS
     *   intent. Tests pass a flag.
     */
    fun selectCandidate(
        manufacturer: String,
        appId: String,
        canResolveVendor: (VendorComponentRef) -> Boolean,
        canResolveAppSettings: (String) -> Boolean
    ): Candidate? {
        val key = manufacturer.trim().lowercase()
        for ((label, refs) in candidatesFor(key)) {
            for (ref in refs) {
                if (canResolveVendor(ref)) {
                    return Candidate.VendorComponent(ref = ref, vendorLabel = label)
                }
            }
        }
        return if (canResolveAppSettings(appId)) {
            Candidate.AppDetailsSettings(packageName = appId)
        } else {
            null
        }
    }

    /**
     * Ordered candidate buckets for a normalized manufacturer key.
     * Order is deterministic and is part of the test surface — do not
     * reorder without updating [VendorGuidanceIntentsTest].
     *
     * Empty list → no vendor bucket matched; caller falls back to
     * app-settings.
     */
    internal fun candidatesFor(key: String): List<Pair<String, List<VendorComponentRef>>> = when {
        key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") ->
            listOf("MIUI" to MIUI)
        key.contains("samsung") ->
            listOf("Samsung" to SAMSUNG)
        key.contains("oneplus") ->
            listOf("OnePlus" to ONEPLUS)
        key.contains("vivo") || key.contains("iqoo") ->
            listOf("Vivo" to VIVO)
        key.contains("oppo") || key.contains("realme") ->
            listOf("Oppo" to OPPO)
        else -> emptyList()
    }

    /** Build a launchable [Intent] for the picked candidate. Boundary
     *  helper — not exercised by JVM tests (Android stubs no-op). */
    fun toIntent(candidate: Candidate): Intent = when (candidate) {
        is Candidate.VendorComponent -> Intent().apply {
            component = ComponentName(candidate.ref.packageName, candidate.ref.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        is Candidate.AppDetailsSettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", candidate.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Production convenience: pulls manufacturer from [Build.MANUFACTURER]
     * and the app id from [context], gates candidates against the live
     * [android.content.pm.PackageManager], and returns the launchable
     * Intent (or `null` if nothing resolves — vanishingly rare on a real
     * device but possible on stripped images).
     */
    fun resolveForCurrent(context: Context): Intent? {
        val pm = context.packageManager
        val candidate = selectCandidate(
            manufacturer = Build.MANUFACTURER ?: "",
            appId = context.packageName,
            canResolveVendor = { ref ->
                val probe = Intent().apply {
                    component = ComponentName(ref.packageName, ref.className)
                }
                pm.resolveActivity(probe, 0) != null
            },
            canResolveAppSettings = { appId ->
                val probe = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", appId, null)
                }
                pm.resolveActivity(probe, 0) != null
            }
        ) ?: return null
        return toIntent(candidate)
    }

    private val MIUI = listOf(
        VendorComponentRef(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        VendorComponentRef(
            "com.miui.securitycenter",
            "com.miui.powercenter.PowerSettings"
        )
    )

    private val SAMSUNG = listOf(
        VendorComponentRef(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity"
        ),
        VendorComponentRef(
            "com.samsung.android.sm",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        VendorComponentRef(
            "com.samsung.android.sm",
            "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"
        )
    )

    private val ONEPLUS = listOf(
        VendorComponentRef(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        ),
        VendorComponentRef(
            "com.oplus.battery",
            "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"
        )
    )

    private val VIVO = listOf(
        VendorComponentRef(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        VendorComponentRef(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        VendorComponentRef(
            "com.iqoo.secure",
            "com.iqoo.secure.MainActivity"
        )
    )

    private val OPPO = listOf(
        VendorComponentRef(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        VendorComponentRef(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        VendorComponentRef(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        )
    )
}
