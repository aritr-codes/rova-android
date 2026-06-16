package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.library.LibraryFilter
import com.aritr.rova.ui.library.rememberLibraryColors

/**
 * spec §5.4 — Library filter chips (data-backed deviation from stale spec wording:
 * ADR-0029 collapsed Portrait/Landscape into Single, so the topology facet surfaces
 * only P+L). All · ★ Favorites · P+L. Favorites + P+L are independent toggles
 * (Checkbox role); All resets both (Button role). Selected state is never color-only —
 * each chip's selected flag + contentDescription carry it (WCAG 2.2 AA).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterChips(
    filter: LibraryFilter,
    onAll: () -> Unit,
    onToggleFavorites: () -> Unit,
    onTogglePl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allActive = !filter.favoritesOnly && filter.topology == null
    val plActive = filter.topology == CaptureTopology.DualShot
    val selTemplate = stringResource(R.string.library_filter_selected_cd)
    val unselTemplate = stringResource(R.string.library_filter_not_selected_cd)

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(
            label = stringResource(R.string.library_filter_all),
            selected = allActive,
            role = Role.Button,
            selTemplate = selTemplate,
            unselTemplate = unselTemplate,
            onClick = onAll,
        )
        Chip(
            label = stringResource(R.string.library_filter_favorites),
            selected = filter.favoritesOnly,
            role = Role.Checkbox,
            selTemplate = selTemplate,
            unselTemplate = unselTemplate,
            onClick = onToggleFavorites,
        )
        Chip(
            label = stringResource(R.string.library_filter_pl),
            selected = plActive,
            role = Role.Checkbox,
            selTemplate = selTemplate,
            unselTemplate = unselTemplate,
            onClick = onTogglePl,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    role: Role,
    selTemplate: String,
    unselTemplate: String,
    onClick: () -> Unit,
) {
    val cd = if (selected) String.format(selTemplate, label) else String.format(unselTemplate, label)
    // M1 (Theme Foundation) — the hairline edge is the active theme's edge token (was Color.White@0.07).
    val chipEdge = rememberLibraryColors().chipEdge
    // Polish P4 — quieter, glass-consistent: unselected chips are transparent with a glass hairline
    // edge; selected stays tonal but flat (no elevation) so chips don't compete with the hero/cards.
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        elevation = FilterChipDefaults.filterChipElevation(elevation = 0.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = chipEdge,
            selectedBorderColor = Color.Transparent,
        ),
        modifier = Modifier.clearAndSetSemantics {
            this.contentDescription = cd
            this.role = role
            this.selected = selected
        },
    )
}
