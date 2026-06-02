package com.aritr.rova.ui.locale

import java.util.Locale

/**
 * One picker entry. [tag] is a BCP-47 language tag, or `null` for the
 * "system default" sentinel (follow the OS locale).
 */
data class LocaleOption(val tag: String?)

/**
 * Pure, framework-free locale model for i18n Phase B (ADR-0023). Holds the
 * canonical list of *real, reviewed* in-app languages and the rules the
 * Settings picker and the persisted-tag coercion both depend on. Unit-testable
 * under `isReturnDefaultValues = true` (no Android types).
 *
 * Pseudolocales (`en_XA`, `ar_XB`) are a debug-only QA tool and are NEVER
 * members of [SUPPORTED_USER_LOCALES] — see ADR-0023.
 */
object AppLocale {

    /**
     * BCP-47 tags of catalogs that actually ship and have been reviewed.
     * Empty this slice → the in-app Language picker stays hidden (no empty
     * promise). A later slice appends one tag (e.g. "es") alongside its
     * `values-es/` catalog and a `<locale>` line in `locales_config.xml`.
     */
    val SUPPORTED_USER_LOCALES: List<String> = emptyList()

    /**
     * Coerce a persisted locale tag to a known-good value. `null`/blank/unknown
     * → `null` (system default), mirroring `themeModeFromStorage`'s defensive
     * coercion. `supported` is injectable for tests; defaults to the shipped list.
     */
    fun localeTagFromStorage(
        raw: String?,
        supported: List<String> = SUPPORTED_USER_LOCALES,
    ): String? {
        if (raw.isNullOrBlank()) return null
        return raw.takeIf { it in supported }
    }

    /** System-default sentinel first, then one entry per supported language. */
    fun languagePickerOptions(
        supported: List<String> = SUPPORTED_USER_LOCALES,
    ): List<LocaleOption> =
        listOf(LocaleOption(null)) + supported.map { LocaleOption(it) }

    /** True only when there is ≥1 real language beyond the system default. */
    fun shouldShowLanguagePicker(options: List<LocaleOption>): Boolean =
        options.any { it.tag != null }

    /** `null` tag → system (no override); otherwise the matching [Locale]. */
    fun localeFor(tag: String?): Locale? = tag?.let { Locale.forLanguageTag(it) }
}
