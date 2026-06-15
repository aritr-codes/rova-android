package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.library.StorageFormat
import com.aritr.rova.ui.library.StorageSummaryFormatter
import com.aritr.rova.ui.library.UsageSummary
import java.util.Locale

/**
 * Polish P6 — compact storage/usage line under the top bar: `128 sessions · 1,842 clips · 18.6 GB`.
 * Lets the user judge footprint and prune. Text-only (no fill-bar): a background recorder has no fixed
 * storage cap, so a % with no denominator would be fabricated. Tabular figures avoid width jitter; the
 * merged contentDescription gives TalkBack a single labelled read (the parts alone are ambiguous).
 */
@Composable
fun LibraryUsageLine(usage: UsageSummary, modifier: Modifier = Modifier) {
    val sessions = pluralStringResource(R.plurals.library_usage_sessions, usage.sessionCount, usage.sessionCount)
    val clips = pluralStringResource(R.plurals.library_usage_clips, usage.clipCount, usage.clipCount)
    val size = StorageFormat.size(usage.totalBytes, Locale.getDefault())
    val text = StorageSummaryFormatter.join(sessions, clips, size)
    val cd = stringResource(R.string.library_usage_cd, text)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(horizontal = LibraryDimens.screenPadH, vertical = 4.dp)
            .semantics { contentDescription = cd },
    )
}
