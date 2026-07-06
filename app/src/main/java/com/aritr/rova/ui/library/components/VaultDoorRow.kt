package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.library.rememberLibraryColors
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.RovaIcons

private val ROW_RADIUS = 14.dp
private val ROW_BORDER = 1.dp
private val ROW_MIN_HEIGHT = 48.dp
private val ROW_PAD_V = 10.dp
private val LOCK_ICON_SIZE = 20.dp
private val CHEVRON_SIZE = 20.dp

/**
 * bento Task 7 — Private Vault as a quiet timeline destination row (was a top-bar icon). Full-width,
 * 16dp outer gutters matching [LibraryDimens.screenPadH], [rememberLibraryColors] fill1/hairline so it
 * re-tints with the theme like every other Library surface. Task 8 places this as a `LazyColumn` item;
 * this composable is just the standalone row.
 */
@Composable
fun VaultDoorRow(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberLibraryColors()
    val textDim = LocalGlassEnvironment.current.palette.textDim
    val shape = RoundedCornerShape(ROW_RADIUS)
    Row(
        modifier
            .fillMaxWidth()
            // Frozen spec `.vault{margin:0 16px 16px}` — 16dp below the door before
            // the first day section (pixel-parity fix, vertical-rhythm pass).
            .padding(start = LibraryDimens.screenPadH, end = LibraryDimens.screenPadH, bottom = 16.dp)
            .heightIn(min = ROW_MIN_HEIGHT)
            .background(colors.fill1, shape)
            .border(ROW_BORDER, colors.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = ROW_PAD_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SemanticIcon(
            glyph = RovaIcons.Lock,
            contentDescription = null,
            role = IconRole.Secondary,
            modifier = Modifier.size(LOCK_ICON_SIZE),
        )
        Text(
            stringResource(R.string.library_vault_door_title),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = textDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            pluralStringResource(R.plurals.library_vault_door_count, count, count),
            fontSize = 11.5.sp,
            color = textDim,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = textDim,
            modifier = Modifier.size(CHEVRON_SIZE),
        )
    }
}
