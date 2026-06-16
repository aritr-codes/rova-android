package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.RovaAnimations
import com.aritr.rova.ui.components.rememberReduceMotion

/**
 * Polish P4 (2026-06-15) — skeleton loading placeholder. Replaces the bare spinner with a column of
 * card-shaped placeholders so the load reads as "Library, populating" rather than a generic wait.
 * Shimmer is reduce-motion gated (WCAG 2.2 AA SC 2.3.3, ADR-0020 / [checkA11yAnimationGated]):
 * [RovaAnimations.pulsingOpacity] self-holds a static alpha under reduced motion, and the explicit
 * [reduce] branch keeps a visible static placeholder regardless — both paths are gate-safe.
 */
@Composable
fun LibraryLoading(modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val shimmerAlpha = if (reduce) 0.12f else RovaAnimations.pulsingOpacity(
        durationMillis = 900, minAlpha = 0.06f, maxAlpha = 0.16f,
    )
    Column(modifier.padding(horizontal = LibraryDimens.screenPadH)) {
        repeat(5) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = LibraryDimens.cardPadV)
                    .clip(RoundedCornerShape(LibraryDimens.cardRadius))
                    .background(Color.White.copy(alpha = shimmerAlpha))
                    .padding(vertical = 28.dp),
            )
        }
    }
}

/**
 * Shared scaffold for the three full-screen states (empty / search-empty / error): theme-native ring
 * illustration + title + body + an optional quiet [action] slot. Per M3 empty-state guidance the
 * action is a TEXT/tonal control supplied by the caller, never a filled CTA that reads as onboarding.
 */
@Composable
private fun LibraryStateScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = LibraryDimens.emptyIconAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        action()
    }
}

/** Genuinely-empty library: quiet text link to Record (NOT a filled CTA — M3 empty-state guidance). */
@Composable
fun LibraryEmpty(onGoToRecord: () -> Unit, modifier: Modifier = Modifier) =
    LibraryStateScaffold(
        icon = Icons.Outlined.VideoLibrary,
        title = stringResource(R.string.library_empty_title),
        body = stringResource(R.string.library_empty_body),
        modifier = modifier,
        action = {
            TextButton(onClick = onGoToRecord) {
                Text(stringResource(R.string.library_empty_go_to_record))
            }
        },
    )

/** Loaded library, but the active search/filter matched nothing — offers a clear-filters action. */
@Composable
fun LibrarySearchEmpty(onClearFilters: () -> Unit, modifier: Modifier = Modifier) =
    LibraryStateScaffold(
        icon = Icons.Outlined.SearchOff,
        title = stringResource(R.string.library_search_empty_title),
        body = stringResource(R.string.library_search_empty_body),
        modifier = modifier,
        action = {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.library_search_empty_cta))
            }
        },
    )

/**
 * M2 — Favorites filter matched nothing. Educational, not "absence": teaches the star gesture (the only
 * place Favoriting is surfaced anywhere in the app), so an empty Favorites tab becomes feature discovery.
 */
@Composable
fun LibraryFavoritesEmpty(onClearFilters: () -> Unit, modifier: Modifier = Modifier) =
    LibraryStateScaffold(
        icon = Icons.Outlined.StarBorder,
        title = stringResource(R.string.library_favorites_empty_title),
        body = stringResource(R.string.library_favorites_empty_body),
        modifier = modifier,
        action = {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.library_search_empty_cta))
            }
        },
    )

/**
 * M2 — DualShot filter matched nothing. Educational: explains what DualShot is. Lighter than Favorites —
 * the create loop completes in the camera, not here — so it informs without a record CTA (Clear filters
 * keeps the user in Library).
 */
@Composable
fun LibraryDualShotEmpty(onClearFilters: () -> Unit, modifier: Modifier = Modifier) =
    LibraryStateScaffold(
        icon = Icons.Outlined.Splitscreen,
        title = stringResource(R.string.library_dualshot_empty_title),
        body = stringResource(R.string.library_dualshot_empty_body),
        modifier = modifier,
        action = {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.library_search_empty_cta))
            }
        },
    )

/**
 * M2 — a multi-facet miss (e.g. Favorites AND DualShot) with no text query. There's no single facet to
 * teach, so the copy is neutral and action-oriented ("Clear a filter…") rather than the search wording —
 * which would be false here (no search is active). Routed from [FilteredEmptyKind.Generic].
 */
@Composable
fun LibraryFilteredEmpty(onClearFilters: () -> Unit, modifier: Modifier = Modifier) =
    LibraryStateScaffold(
        icon = Icons.Outlined.SearchOff,
        title = stringResource(R.string.library_filtered_empty_title),
        body = stringResource(R.string.library_filtered_empty_body),
        modifier = modifier,
        action = {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.library_search_empty_cta))
            }
        },
    )

/**
 * Read-error state. Defined for completeness; currently UNROUTED — there is no VM error signal yet
 * (see [com.aritr.rova.ui.library.LibraryStateKind]). Wire it once such a signal exists.
 */
@Composable
fun LibraryError(modifier: Modifier = Modifier) =
    LibraryStateScaffold(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.library_error_title),
        body = stringResource(R.string.library_error_body),
        modifier = modifier,
    )
