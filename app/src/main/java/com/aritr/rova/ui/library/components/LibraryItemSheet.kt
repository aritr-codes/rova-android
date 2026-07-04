package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.ui.library.rememberLibraryColors
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaIcons

/**
 * bento Task 9 — the frozen details sheet (`docs/design/library-bento.html`, selection-ⓘ entry
 * only: the old long-press-opens-sheet + sheet-carried "Select"/"Play"/"View settings" rows are
 * gone). Anatomy: edge-to-edge modal sheet (26dp top radius, `surfaceHi`→`surface` gradient),
 * a 40×4.5dp grab handle, an explicit ≥48dp close button floating over the hero (32dp dark dot +
 * X — a modal can't rely on drag/scrim alone), a 16:9 hero (single = one frame + centered 56dp
 * play disc on the [DialogActionColors]-resolved CTA gradient; DualShot = two panes, Portrait
 * left, each with its own play disc + orientation label pill), an inline-rename title, two lines
 * of quiet facts prose, three action rows (favorite/vault/share), and a separated danger zone
 * (delete). `LibrarySessionConfigDialog` ("view settings") is a known, owner-accepted delta — not
 * in the frozen sheet (spec `2026-07-04-library-bento-compose-spec.md` §3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemSheet(
    isFavorite: Boolean,
    movable: Boolean,
    isDualShot: Boolean,
    title: String,
    factsLine1: String,
    factsLine2: String,
    heroThumbnail: Bitmap?,
    portraitThumbnail: Bitmap?,
    landscapeThumbnail: Bitmap?,
    durationPillLabel: String,
    playLabel: String,
    portraitLabel: String,
    landscapeLabel: String,
    closeLabel: String,
    renameLabel: String,
    renameFieldHint: String,
    favoriteLabel: String,
    unfavoriteLabel: String,
    vaultLabel: String,
    vaultUnavailableReason: String?,
    shareLabel: String,
    deleteLabel: String,
    onPlay: (side: String?) -> Unit,
    onRename: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveToVault: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val libraryColors = rememberLibraryColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = null,
        shape = RectangleShape,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = LibraryDimens.sheetCornerRadiusV2, topEnd = LibraryDimens.sheetCornerRadiusV2))
                .background(libraryColors.sheetBackground)
                .navigationBarsPadding(),
        ) {
            Column {
                // Grab handle (frozen 40×4.5dp) — no functional drag; the explicit close button
                // below is the sole dismiss affordance a modal needs (drag/scrim aren't reliable alone).
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 4.5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)),
                    )
                }
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 20.dp),
                ) {
                    SheetHero(
                        isDualShot = isDualShot,
                        heroThumbnail = heroThumbnail,
                        portraitThumbnail = portraitThumbnail,
                        landscapeThumbnail = landscapeThumbnail,
                        durationPillLabel = durationPillLabel,
                        playLabel = playLabel,
                        portraitLabel = portraitLabel,
                        landscapeLabel = landscapeLabel,
                        ctaGradient = libraryColors.heroCtaGradient,
                        ctaInk = libraryColors.heroCtaInk,
                        onPlay = onPlay,
                    )
                    SheetTitleRow(title = title, renameLabel = renameLabel, renameFieldHint = renameFieldHint, onRename = onRename)
                    Text(
                        factsLine1 + "\n" + factsLine2,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 21.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(LibraryDimens.heroRadius))
                            .background(libraryColors.fill1),
                    ) {
                        ActionRow(
                            glyph = LibraryIconSpec.favoriteGlyph(isFavorite),
                            label = if (isFavorite) unfavoriteLabel else favoriteLabel,
                            iconRole = IconRole.Accent,
                            onClick = onToggleFavorite,
                        )
                        HorizontalDivider(color = libraryColors.hairline)
                        ActionRow(
                            glyph = RovaIcons.Vault,
                            label = vaultLabel,
                            enabled = movable,
                            reason = vaultUnavailableReason,
                            onClick = onMoveToVault,
                        )
                        HorizontalDivider(color = libraryColors.hairline)
                        ActionRow(glyph = RovaIcons.Share, label = shareLabel, onClick = onShare)
                    }
                    Column(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(LibraryDimens.heroRadius))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.07f)),
                    ) {
                        ActionRow(
                            glyph = RovaIcons.Delete,
                            label = deleteLabel,
                            status = LibraryIconSpec.deleteStatus(destructive = true),
                            onClick = onDelete,
                        )
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(48.dp),
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SHEET_CLOSE_DOT_BG),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = closeLabel,
                        tint = libraryColors.overlayText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Frozen close-dot backing (`Color(0x9E060308)` — the mockup's `--media-scrim` over the hero). */
private val SHEET_CLOSE_DOT_BG = Color(0x9E060308)

/**
 * 16:9 hero. Single = one frame + a centered 56dp play disc on the resolved CTA gradient, duration
 * pill bottom-end. DualShot = two panes (Portrait left), each its own 44dp play disc + orientation
 * label pill — [onPlay] receives the tapped [com.aritr.rova.service.dualrecord.VideoSide]`.name`
 * token (dual) or `null` (single), mirroring [BentoTile]'s pane-token convention.
 */
@Composable
private fun SheetHero(
    isDualShot: Boolean,
    heroThumbnail: Bitmap?,
    portraitThumbnail: Bitmap?,
    landscapeThumbnail: Bitmap?,
    durationPillLabel: String,
    playLabel: String,
    portraitLabel: String,
    landscapeLabel: String,
    ctaGradient: androidx.compose.ui.graphics.Brush,
    ctaInk: Color,
    onPlay: (String?) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(LibraryDimens.heroRadius)),
    ) {
        if (isDualShot) {
            val portraitCd = "$playLabel $portraitLabel"
            val landscapeCd = "$playLabel $landscapeLabel"
            Row(Modifier.fillMaxSize()) {
                HeroPane(portraitThumbnail, portraitLabel, portraitCd, ctaGradient, ctaInk, Modifier.weight(1f).fillMaxSize()) { onPlay("PORTRAIT") }
                Spacer(Modifier.width(2.dp))
                HeroPane(landscapeThumbnail, landscapeLabel, landscapeCd, ctaGradient, ctaInk, Modifier.weight(1f).fillMaxSize()) { onPlay("LANDSCAPE") }
            }
        } else {
            VideoFrame(heroThumbnail, Modifier.fillMaxSize())
            PlayDisc(
                ctaGradient = ctaGradient,
                ctaInk = ctaInk,
                contentDescription = null,
                size = 56.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(onClickLabel = null) { onPlay(null) }
                    .semantics { role = Role.Button; contentDescription = playLabel },
            )
            OverlayPill(durationPillLabel, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }
    }
}

@Composable
private fun HeroPane(
    thumbnail: Bitmap?,
    label: String,
    a11yLabel: String,
    ctaGradient: androidx.compose.ui.graphics.Brush,
    ctaInk: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.clickable(onClickLabel = null) { onClick() }.semantics { role = Role.Button; contentDescription = a11yLabel }) {
        VideoFrame(thumbnail, Modifier.fillMaxSize())
        PlayDisc(ctaGradient, ctaInk, contentDescription = null, size = 44.dp, modifier = Modifier.align(Alignment.Center))
        OverlayPill(label, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun PlayDisc(
    ctaGradient: androidx.compose.ui.graphics.Brush,
    ctaInk: Color,
    contentDescription: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(ctaGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = contentDescription,
            tint = ctaInk,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Title (18sp/700) + pencil → inline rename. IME-done/Enter commits; back cancels rename only. */
@Composable
private fun SheetTitleRow(title: String, renameLabel: String, renameFieldHint: String, onRename: (String) -> Unit) {
    var renaming by remember(title) { mutableStateOf(false) }
    var text by remember(title) { mutableStateOf(title) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun commit() {
        onRename(text)
        renaming = false
    }
    fun cancel() {
        text = title
        renaming = false
    }

    BackHandler(enabled = renaming) { cancel() }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (renaming) {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(renameFieldHint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); commit() }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(onClick = { if (renaming) commit() else renaming = true }, modifier = Modifier.size(44.dp)) {
            SemanticIconEdit(renameLabel)
        }
    }
}

@Composable
private fun SemanticIconEdit(renameLabel: String) {
    com.aritr.rova.ui.components.SemanticIcon(
        glyph = RovaIcons.Edit,
        contentDescription = renameLabel,
        role = IconRole.Secondary,
        modifier = Modifier.size(20.dp),
    )
}

/** Action row (≥50dp, frozen). Mirrors the pre-bento `SheetRow` shape with the new min-height token. */
@Composable
private fun ActionRow(
    glyph: com.aritr.rova.ui.theme.RovaGlyph,
    label: String,
    enabled: Boolean = true,
    reason: String? = null,
    iconRole: IconRole = IconRole.Default,
    status: IconStatus? = null,
    onClick: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val labelColor = when {
        status == IconStatus.Danger -> MaterialTheme.colorScheme.error
        !enabled -> baseColor.copy(alpha = 0.38f)
        else -> baseColor
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        com.aritr.rova.ui.components.SemanticIcon(
            glyph = glyph,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            role = if (!enabled) IconRole.Disabled else iconRole,
            status = status,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, color = labelColor)
            if (!enabled && reason != null) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
        }
    }
}
