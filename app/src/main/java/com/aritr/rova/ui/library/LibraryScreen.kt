package com.aritr.rova.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.R
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.components.LibraryDayHeader
import com.aritr.rova.ui.library.components.LibraryEmpty
import com.aritr.rova.ui.library.components.LibraryGridCard
import com.aritr.rova.ui.library.components.LibraryHeroCard
import com.aritr.rova.ui.library.components.LibraryListRow
import com.aritr.rova.ui.library.components.LibraryLoading
import com.aritr.rova.ui.library.components.LibraryTopBar
import com.aritr.rova.ui.library.components.statusBadgeLabel
import com.aritr.rova.ui.screens.HistoryViewModel
import java.util.Locale
import java.util.TimeZone

private const val GRID_COLUMNS = 2

/**
 * spec §5 — redesigned Library browse surface (hero + grid/list, day-grouped,
 * glass chrome). Pure layout over [HistoryViewModel.libraryUiState]; all derived
 * values come from the tested pure helpers (LibraryQuery / LibraryDayGrouping /
 * TileSemantics). The thumbnail bitmap + nav identity are sourced from the VM's
 * [HistoryViewModel.items] (matched by stableKey). Recovery cards + warning strip
 * are rendered by the caller (retained header — see Task 13).
 *
 * Hero invariant (owner adjustment 1): the newest recording renders in EXACTLY one
 * place — `collection` is built with `hero?.stableKey`, so LibraryQuery.collection
 * drops the hero from the day-grouped list. Pinned by LibraryQueryHeroDedupTest.
 */
@Composable
fun LibraryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onOpenPlayer: (sessionId: String, side: VideoSide?) -> Unit = { _, _ -> },
    onShare: (stableKey: String) -> Unit = {},
    onNavigateToRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.libraryUiState.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    // reduce-motion seam wired per ADR-0020: this v1 grid/list adds NO item-
    // placement animation, so there is nothing to gate yet. If any animate* is
    // added to a tile later, wrap it `if (!reduceMotion) …` (checkA11yAnimationGated
    // only fires on present animations).
    @Suppress("UNUSED_VARIABLE")
    val reduceMotion = rememberReduceMotion()

    val byKey = remember(items) { items.associateBy { it.stableKey } }
    val locale = Locale.getDefault()
    val tz = TimeZone.getDefault()
    val nowMillis = remember(ui.rows) { System.currentTimeMillis() }

    val frag = TileSemantics.Fragments(
        durationWord = stringResource(R.string.library_a11y_duration),
        recoveredWord = stringResource(R.string.library_badge_recovered),
        interruptedWord = stringResource(R.string.library_badge_interrupted),
        dualWord = stringResource(R.string.library_badge_pl),
    )
    val recoveredLabel = stringResource(R.string.library_badge_recovered)
    val interruptedLabel = stringResource(R.string.library_badge_interrupted)
    val plLabel = stringResource(R.string.library_badge_pl)
    val eyebrow = stringResource(R.string.library_eyebrow_latest)
    val favoriteLabel = stringResource(R.string.library_action_favorite)
    val unfavoriteLabel = stringResource(R.string.library_action_unfavorite)
    val shareLabel = stringResource(R.string.library_action_share)

    val hero = remember(ui.rows) { LibraryQuery.hero(ui.rows) }
    val collection = remember(ui.rows) {
        LibraryQuery.collection(ui.rows, LibrarySort.NEWEST, LibraryFilter(), hero?.stableKey)
    }
    val groups = remember(collection, nowMillis) {
        LibraryDayGrouping.group(collection, nowMillis, locale, tz)
    }

    fun play(stableKey: String) {
        val item = byKey[stableKey] ?: return
        val sid = item.sessionId ?: return
        onOpenPlayer(sid, item.side)
    }

    @Composable
    fun renderHero(row: LibraryRow) {
        LibraryHeroCard(
            row = row,
            thumbnail = byKey[row.stableKey]?.thumbnail,
            eyebrow = eyebrow,
            playDescription = TileSemantics.describe(row, frag),
            favoriteLabel = favoriteLabel,
            unfavoriteLabel = unfavoriteLabel,
            shareLabel = shareLabel,
            onPlay = { play(row.stableKey) },
            onFavorite = { viewModel.toggleFavorite(row.stableKey) },
            onShare = { onShare(row.stableKey) },
        )
    }

    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            title = stringResource(R.string.history_title),
            viewMode = ui.viewMode,
            gridLabel = stringResource(R.string.library_view_grid),
            listLabel = stringResource(R.string.library_view_list),
            onToggleView = {
                viewModel.setViewMode(
                    if (ui.viewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID,
                )
            },
        )

        when {
            !ui.hasLoaded -> LibraryLoading(Modifier.fillMaxSize())
            ui.rows.isEmpty() -> LibraryEmpty(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
                cta = stringResource(R.string.library_empty_cta),
                onStartRecording = onNavigateToRecord,
            )
            ui.viewMode == LibraryViewMode.GRID -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .semantics {
                        isTraversalGroup = true
                        collectionInfo = CollectionInfo(rowCount = -1, columnCount = GRID_COLUMNS)
                    },
            ) {
                if (hero != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { renderHero(hero) }
                }
                groups.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-${group.label}") {
                        LibraryDayHeader(group.label, group.sizeTotalLabel)
                    }
                    itemsIndexed(group.rows, key = { _, r -> r.stableKey }) { index, row ->
                        LibraryGridCard(
                            row = row,
                            thumbnail = byKey[row.stableKey]?.thumbnail,
                            tileDescription = TileSemantics.describe(row, frag),
                            statusLabel = statusBadgeLabel(row.badge, recoveredLabel, interruptedLabel),
                            plLabel = plLabel,
                            onClick = { play(row.stableKey) },
                            modifier = Modifier.padding(4.dp),
                            itemSemantics = {
                                collectionItemInfo = CollectionItemInfo(
                                    rowIndex = index / GRID_COLUMNS,
                                    rowSpan = 1,
                                    columnIndex = index % GRID_COLUMNS,
                                    columnSpan = 1,
                                )
                            },
                        )
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (hero != null) {
                    item(key = "hero-${hero.stableKey}") { renderHero(hero) }
                }
                groups.forEach { group ->
                    item(key = "hdr-${group.label}") {
                        LibraryDayHeader(group.label, group.sizeTotalLabel)
                    }
                    items(group.rows, key = { it.stableKey }) { row ->
                        LibraryListRow(
                            row = row,
                            thumbnail = byKey[row.stableKey]?.thumbnail,
                            tileDescription = TileSemantics.describe(row, frag),
                            durationFallback = "—",
                            onClick = { play(row.stableKey) },
                        )
                    }
                }
            }
        }
    }
}
