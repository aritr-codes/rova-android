package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * spec §5.2 — glass bottom batch bar: Share · Vault · Favorite · Delete (no Export, ADR-0030). Polish pass:
 * icon + label per action for affordance, nav-bar inset so the bar clears the gesture/home indicator.
 * Vault is disabled (greyed, reason in its merged label) when [vaultEnabled] is false (P+L not movable).
 * Each action is a Button-role clickable ≥48dp (checkA11yClickableHasRole + checkA11yTargetSizeToken).
 */
@Composable
fun LibraryBatchBar(
    shareLabel: String,
    vaultLabel: String,
    vaultDisabledLabel: String,
    favoriteLabel: String,
    deleteLabel: String,
    vaultEnabled: Boolean,
    onShare: () -> Unit,
    onVault: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatchAction(RovaIcons.Share, shareLabel, enabled = true, onClick = onShare)
            BatchAction(
                RovaIcons.Vault,
                vaultLabel,
                enabled = vaultEnabled,
                disabledDescription = vaultDisabledLabel,
                onClick = onVault,
            )
            BatchAction(RovaIcons.FavoriteOn, favoriteLabel, enabled = true, onClick = onFavorite)
            BatchAction(RovaIcons.Delete, deleteLabel, enabled = true, onClick = onDelete)
        }
    }
}

@Composable
private fun BatchAction(
    glyph: RovaGlyph,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    iconRole: IconRole = IconRole.Default,
    disabledDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val a11y = if (enabled) label else (disabledDescription ?: label)
    Column(
        modifier
            .width(72.dp)
            .selectable(
                selected = false,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button; contentDescription = a11y }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SemanticIcon(
            glyph = glyph,
            contentDescription = null,
            modifier = Modifier.size(LibraryDimens.actionIcon),
            role = if (enabled) iconRole else IconRole.Disabled,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
