package com.aritr.rova.utils

import android.util.Log
import com.aritr.rova.BuildConfig

/**
 * Centralised logger that gates debug/verbose output behind BuildConfig.DEBUG
 * so release builds don't leak internal paths or state details to logcat.
 */
object RovaLog {
    private const val TAG = "Rova"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
