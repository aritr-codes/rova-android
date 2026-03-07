package com.aritr.rova.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    /**
     * Returns true if the app is already exempt from battery optimizations —
     * i.e., no action is needed.
     */
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Builds the intent that opens the system "Disable battery optimization" dialog
     * for this app. The caller is responsible for catching ActivityNotFoundException.
     */
    fun buildRequestIntent(packageName: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
}
