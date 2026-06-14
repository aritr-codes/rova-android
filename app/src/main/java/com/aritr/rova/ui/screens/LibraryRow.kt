package com.aritr.rova.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.PreviewActivity
import com.aritr.rova.ui.recovery.RecoveryCardKind
import com.aritr.rova.ui.recovery.RecoveryCardList
import com.aritr.rova.ui.recovery.RecoveryViewModel
import com.aritr.rova.ui.recovery.VendorGuidanceIntents
import com.aritr.rova.ui.recovery.recoveryViewModelFactory
import com.aritr.rova.ui.share.safeShareUri
import com.aritr.rova.ui.theme.RovaTheme
import com.aritr.rova.ui.warnings.HistoryWarningSheetHost
import com.aritr.rova.ui.warnings.HistoryWarningStrip
import com.aritr.rova.ui.warnings.WarningCenterViewModel
import com.aritr.rova.ui.warnings.WarningId
import com.aritr.rova.ui.warnings.WarningScreen
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Slice 4 — list-row redesign for the Library. Replaces the
 * decorative `ElevatedCard` rows with a soft Surface row whose
 * primary text is the human date · time pulled from
 * [HistoryRowFormatters.formatPrimaryDateTime]; the filename
 * survives as a small monospace caption so users debugging an
 * artifact can still locate it.
 *
 * Interaction contract:
 *   - row tap → opens playback (`onPlay`) unless selection mode is
 *     active, in which case it toggles this row's selection.
 *   - row long-press → enters or toggles multi-select (`onToggleSelection`).
 *   - per-row 48 dp `MoreVert` overflow → opens the Phase 2.2 row
 *     menu with Open / Edit / View Settings when selection mode is
 *     NOT active. While selection mode is active, the overflow
 *     falls back to toggling this row's selection and must not open
 *     the menu (so the multi-select gesture is never shadowed).
 *   - the overflow remains a 48 dp `IconButton` with its own
 *     `contentDescription` describing its scope (`"More actions for
 *     May 4 · 2:22 PM recording"`).
 *
 * The whole row's `combinedClickable` carries a single
 * `contentDescription` so TalkBack reads `"Recording May 4 · 2:22 PM,
 * quality FHD, size 82.4 MB"` on focus, not the filename string.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    item: VideoItem,
    nowMillis: Long,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPlay: () -> Unit,
    onMenuOpen: () -> Unit = {},
    onMenuEdit: () -> Unit = {},
    onMenuViewSettings: () -> Unit = {},
    // B5 / ADR-0025 (Task 22) — non-null only for normal (PUBLIC) Library
    // rows that can be hidden into the vault. Null on the Vault screen and
    // for legacy file-only rows (no sessionId), so the "Move to vault"
    // overflow item is hidden there.
    onMenuMoveToVault: (() -> Unit)? = null,
    // B5 / ADR-0025 (Task 22) — non-null only on the unlocked Vault screen
    // for movable vault rows. Republishes the recording to public storage
    // and clears its vault state. Null in the Library.
    onMenuMoveOutOfVault: (() -> Unit)? = null,
    // B5 / ADR-0025 — non-null only on the unlocked Vault screen. Permanently
    // deletes the recording (its only copy — there is no gallery backup).
    // The caller surfaces a strong confirmation before invoking. Null in the
    // Library, which keeps its own multi-select delete flow.
    onMenuDelete: (() -> Unit)? = null
) {
    // B4c — read date/size/name via the effective accessors so SAF rows
    // (file == null) use their DocumentFile-sourced metadata instead of
    // NPE-ing on a File deref. File rows are byte-identical.
    val lastModified = item.effectiveLastModified()
    val sizeBytes = item.effectiveSize()
    val primary = remember(item.stableKey, nowMillis) {
        HistoryRowFormatters.formatPrimaryDateTime(lastModified)
    }
    val time24 = remember(item.stableKey) {
        HistoryRowFormatters.formatTime24(lastModified)
    }
    val sizeText = remember(item.stableKey) {
        HistoryRowFormatters.formatSize(sizeBytes)
    }
    val rowA11y = remember(primary, item.stableKey, item.resolution) {
        HistoryRowFormatters.formatRowAccessibility(
            primaryDateTime = primary,
            sizeBytes = sizeBytes,
            quality = item.resolution
        )
    }
    val moreA11y = remember(primary) {
        HistoryRowFormatters.formatMoreActionsLabel(primary)
    }
    val rowBackground = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = rowBackground,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onToggleSelection,
                role = Role.Button,
            )
            .semantics { contentDescription = rowA11y }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Spacer(modifier = Modifier.size(4.dp))
            }
            Box {
                VideoThumbnail(
                    thumbnail = item.thumbnail,
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                ) {
                    Text(
                        text = item.resolution,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = time24,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.effectiveName(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Slice 4 — explicit per-row overflow trigger. 48 dp
            // touch target via the M3 IconButton default. Long-press
            // on the row still enters multi-select; the overflow icon
            // now opens the Phase 2.2 dropdown menu (Open / Edit /
            // View Settings). In selection mode the icon falls back
            // to the legacy "tap to toggle" so the menu cannot
            // shadow the multi-select gesture.
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(
                    onClick = {
                        if (isSelectionMode) onToggleSelection() else menuExpanded = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = moreA11y,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_action_open)) },
                        onClick = {
                            menuExpanded = false
                            onMenuOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_action_edit)) },
                        onClick = {
                            menuExpanded = false
                            onMenuEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_action_view_settings)) },
                        onClick = {
                            menuExpanded = false
                            onMenuViewSettings()
                        }
                    )
                    // B5 / ADR-0025 (Task 22) — move-in entry, only on
                    // movable PUBLIC rows (onMenuMoveToVault non-null).
                    onMenuMoveToVault?.let { moveToVault ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vault_move_in)) },
                            onClick = {
                                menuExpanded = false
                                moveToVault()
                            }
                        )
                    }
                    // B5 / ADR-0025 (Task 22) — move-out entry, only on the
                    // unlocked Vault screen (onMenuMoveOutOfVault non-null).
                    onMenuMoveOutOfVault?.let { moveOut ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vault_move_out)) },
                            onClick = {
                                menuExpanded = false
                                moveOut()
                            }
                        )
                    }
                    // B5 / ADR-0025 — permanent delete, only on the unlocked
                    // Vault screen (onMenuDelete non-null). Destructive: tinted
                    // with the error color. The caller shows a strong
                    // irreversible-delete confirmation before doing anything.
                    onMenuDelete?.let { delete ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.vault_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                delete()
                            }
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun VideoThumbnail(thumbnail: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(28.dp)
            )
        } else {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = stringResource(R.string.history_play_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
