package com.aritr.rova.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * Thin framework seam that applies a chosen locale (ADR-0023). Two API tiers,
 * no AppCompat:
 *
 *  - **API 33+** — [LocaleManager.setApplicationLocales]. The framework persists
 *    the per-app locale, recreates the Activity, and keeps the app in sync with
 *    the Android 13+ system per-app-language screen.
 *  - **API 24–32** — the locale is applied in [android.app.Activity.attachBaseContext]
 *    via [wrapContext]; [apply] just triggers [Activity.recreate] so the wrapped
 *    base context is re-attached from the freshly persisted tag.
 *
 * The persisted tag in `RovaSettings.localeTag` is the single source of truth;
 * callers MUST write it *before* calling [apply] so the 24–32 recreate path
 * reads the new value.
 */
object LocaleApplier {

    /** Apply [tag] (null = system default). Caller persists the tag first. */
    fun apply(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            manager.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
            // Framework recreates the Activity itself.
        } else {
            (context as? Activity)?.recreate()
        }
    }

    /**
     * API 24–32 only: wrap [base] with a [Configuration] pinned to the persisted
     * locale so Compose `stringResource` resolves the override. On API 33+ the
     * locale is already on [base] (LocaleManager applied it), so return it as-is.
     */
    fun wrapContext(base: Context, tag: String?): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = AppLocale.localeFor(tag) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
