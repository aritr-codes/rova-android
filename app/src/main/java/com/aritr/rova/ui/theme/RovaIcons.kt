package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Canonical concept→glyph map (ADR-0031 §2/§5). One concept → exactly one glyph. Bespoke System-D
 * concepts resolve to a [RovaGlyph] (two-layer); concepts still on stock Material stay [RovaIcon]
 * until authored in a later slice.
 */
data class RovaIcon(val glyph: ImageVector, val status: IconStatus? = null)

object RovaIcons {
    // ── Bespoke System-D (RovaGlyph) ──
    val Library: RovaGlyph = RovaGlyphs.Library
    val Settings: RovaGlyph = RovaGlyphs.Settings
    val Sort: RovaGlyph = RovaGlyphs.Sort
    val Record: RovaGlyph = RovaGlyphs.Record
    val DualShot: RovaGlyph = RovaGlyphs.DualShot
    val Vault: RovaGlyph = RovaGlyphs.Vault
    val Recovery: RovaGlyph = RovaGlyphs.Recovery
    val DualSight: RovaGlyph = RovaGlyphs.DualSight
    val BackgroundRecord: RovaGlyph = RovaGlyphs.BackgroundRecord
    val Merge: RovaGlyph = RovaGlyphs.Merge
    val OrientationPortrait: RovaGlyph = RovaGlyphs.OrientationPortrait
    val OrientationLandscape: RovaGlyph = RovaGlyphs.OrientationLandscape
    val Single: RovaGlyph = RovaGlyphs.Single
    val FollowDevice: RovaGlyph = RovaGlyphs.FollowDevice

    // ── Still stock Material (RovaIcon), authored in a later slice ──
    val View = RovaIcon(Icons.Default.GridView)
    val Play = RovaIcon(RovaGlyphs.Play)
    val WarningStatus = RovaIcon(Icons.Default.WarningAmber, status = IconStatus.Warning)
    val NotificationsSetting = RovaIcon(Icons.Default.Notifications)
}
