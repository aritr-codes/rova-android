package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Canonical concept→glyph map (ADR-0031 §2/§5). One concept → exactly one glyph. Bespoke System-D
 * concepts resolve to a [RovaGlyph] (two-layer); the few still on stock Material stay [RovaIcon]
 * until authored in a later slice. Each entry's KDoc names how it is meant to be tinted at the
 * call-site (role vs locked status) — the map carries the locked status itself only where the
 * concept IS a status (e.g. [WarningStatus]).
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
    // FAB lifecycle (board-3 FB): rec_disc = [Record] above; the rest:
    val RecordRing: RovaGlyph = RovaGlyphs.RecordRing
    val Waiting: RovaGlyph = RovaGlyphs.Waiting
    val Processing: RovaGlyph = RovaGlyphs.ProcArc
    val ProcessingDots: RovaGlyph = RovaGlyphs.ProcDots
    val OrientationPortrait: RovaGlyph = RovaGlyphs.OrientationPortrait
    val OrientationLandscape: RovaGlyph = RovaGlyphs.OrientationLandscape
    val Single: RovaGlyph = RovaGlyphs.Single
    val FollowDevice: RovaGlyph = RovaGlyphs.FollowDevice

    // ── PR-3 everyday-action glyphs (role-tinted; consume notes in KDoc) ──
    /** Library/global search field. */
    val Search: RovaGlyph = RovaGlyphs.Search
    /** Share sheet trigger. */
    val Share: RovaGlyph = RovaGlyphs.Share
    /** Delete action — neutral glyph; consume with `status = IconStatus.Danger` in destructive UI. */
    val Delete: RovaGlyph = RovaGlyphs.Delete
    /** Favorite (off). Consume with `role = IconRole.Accent`; swap to [FavoriteOn] when set. */
    val Favorite: RovaGlyph = RovaGlyphs.Favorite
    /** Favorite (on). Consume with `role = IconRole.Accent`. */
    val FavoriteOn: RovaGlyph = RovaGlyphs.FavoriteOn
    /** Multi-select / selected check. */
    val Select: RovaGlyph = RovaGlyphs.Select
    /** Pause (player). */
    val Pause: RovaGlyph = RovaGlyphs.Pause
    /** Edit / rename. */
    val Edit: RovaGlyph = RovaGlyphs.Edit
    /** Theme picker. */
    val Theme: RovaGlyph = RovaGlyphs.Theme
    /** View / layout mode (2×2 grid). Was Icons.Default.GridView. */
    val View: RovaGlyph = RovaGlyphs.View
    /** Notifications setting (enabled toggle/destination). Was Icons.Default.Notifications. */
    val NotificationsSetting: RovaGlyph = RovaGlyphs.NotifBell
    /** Notifications setting (disabled / muted). */
    val NotificationsOff: RovaGlyph = RovaGlyphs.NotifOff

    // ── Status concept — the locked status travels WITH the map entry (codex 2026-06-19) ──
    /**
     * Warning STATUS. WarnTriangle is mono, so its single layer is exposed as the [RovaIcon]
     * image vector with the locked [IconStatus.Warning] baked in — a call-site reads `.glyph`
     * and `.status` and cannot forget the amber lock. Was Icons.Default.WarningAmber.
     */
    val WarningStatus = RovaIcon(RovaGlyphs.WarnTriangle.outline, status = IconStatus.Warning)

    // ── Still stock Material (RovaIcon), authored in a later slice ──
    // "View settings" on a library item shows that recording's read-only capture config — per-item
    // details/metadata, not app preferences → Info (ⓘ), not a gear (owner 2026-06-17).
    val Details = RovaIcon(Icons.Outlined.Info)
    val Play = RovaIcon(RovaGlyphs.Play)
}
