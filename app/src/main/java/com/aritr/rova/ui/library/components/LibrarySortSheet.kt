package com.aritr.rova.ui.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.library.LibrarySort
import com.aritr.rova.ui.theme.IconRole

/**
 * spec §5.4 — glass sort sheet. One row per [LibrarySort]; the active sort carries a
 * check + `selected` + a `stateDescription` ("Current sort: …") so it isn't color-only
 * (WCAG 2.2 AA, ADR-0020). Each row is a Button-role clickable ≥48 dp
 * (checkA11yClickableHasRole + checkA11yTargetSizeToken).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySortSheet(
    current: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.library_sort_title),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val rows = listOf(
            LibrarySort.NEWEST to stringResource(R.string.library_sort_newest),
            LibrarySort.OLDEST to stringResource(R.string.library_sort_oldest),
            LibrarySort.LONGEST to stringResource(R.string.library_sort_longest),
            LibrarySort.LARGEST to stringResource(R.string.library_sort_largest),
        )
        val currentCdTemplate = stringResource(R.string.library_sort_current_cd)
        rows.forEach { (sort, label) ->
            val isCurrent = sort == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onSelect(sort) }
                    .semantics {
                        role = Role.Button
                        selected = isCurrent
                        if (isCurrent) stateDescription = String.format(currentCdTemplate, label)
                    }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                // Check is always laid out; alpha hides it when not current (the row's
                // `selected` + stateDescription carry selection non-visually, WCAG 2.2 AA).
                SemanticIcon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    role = IconRole.Accent,
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (isCurrent) 1f else 0f),
                )
                Spacer(Modifier.width(20.dp))
                Text(label)
            }
        }
    }
}
