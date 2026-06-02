package com.aritr.rova.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A resource-backed, framework-free text token (ADR-0022).
 *
 * Lets pure helpers / ViewModels that own arguments or plural counts decide
 * *which* copy to show without resolving (and thus freezing) localized English —
 * Compose and the service each resolve the same token at their own edge. A bare
 * `@StringRes Int` is preferred when a helper only selects fixed copy; reach for
 * `UiText` only when args or plurals are involved.
 */
sealed interface UiText {
    data class Str(@StringRes val id: Int) : UiText
    data class StrArgs(@StringRes val id: Int, val args: List<Any>) : UiText

    /**
     * A plural string. [quantity] drives grammatical-number selection; if the
     * format string contains a `%d`, [args] must also include [quantity] as its
     * first element (Android's count-is-passed-twice convention — once to pick
     * the rule, once to fill `%d`).
     */
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Str -> stringResource(id)
    is UiText.StrArgs -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *args.toTypedArray())
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Str -> context.getString(id)
    is UiText.StrArgs -> context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, quantity, *args.toTypedArray())
}
