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

    // ── 5b-1 new concepts (Settings / Warnings / Onboarding surfaces; role-tinted) ──
    // T2/T3 glyphs authored in RovaGlyphs; here they get a one-concept→one-glyph map entry.
    // Each is a bare RovaGlyph (role-tinted at the call-site) — NOT a status (the two status
    // concepts below carry their lock). Camera flip migrates onto [FlipCam] in 5b-2…5b-5.
    /** Thermal-autostop concept glyph (status tint applied at the call-site, not locked here). */
    val Thermal: RovaGlyph = RovaGlyphs.Thermal
    /** Storage capacity / storage-insufficient concept glyph. */
    val Storage: RovaGlyph = RovaGlyphs.Storage
    /** Battery low. */
    val BatteryLow: RovaGlyph = RovaGlyphs.BatteryLow
    /** Battery saver / power-save mode. */
    val BatterySaver: RovaGlyph = RovaGlyphs.BatterySaver
    /** Power mode (battery-optimisation exemption). */
    val PowerMode: RovaGlyph = RovaGlyphs.PowerMode
    /** Exact-alarm permission denied / alarm disabled. */
    val AlarmOff: RovaGlyph = RovaGlyphs.AlarmOff
    /** Camera off / blocked (slashed). */
    val CameraOff: RovaGlyph = RovaGlyphs.CameraOff
    /** Camera permission needed (no slash). */
    val CameraPermission: RovaGlyph = RovaGlyphs.CameraPermission
    /** Microphone off / muted (slashed). */
    val MicOff: RovaGlyph = RovaGlyphs.MicOff
    /** Dark-mode theme concept. */
    val DarkMode: RovaGlyph = RovaGlyphs.DarkMode
    /** Language / locale picker. */
    val Language: RovaGlyph = RovaGlyphs.Language
    /** Quality / resolution preset. */
    val Quality: RovaGlyph = RovaGlyphs.Quality
    /** Timer / segment length. */
    val Timer: RovaGlyph = RovaGlyphs.Timer
    /** Recording schedule / daily window. */
    val Schedule: RovaGlyph = RovaGlyphs.Schedule
    /** Lock (generic; role-tinted, never a status). */
    val Lock: RovaGlyph = RovaGlyphs.Lock
    /** Vibration / haptics. */
    val Vibration: RovaGlyph = RovaGlyphs.Vibration
    /** This device. */
    val Device: RovaGlyph = RovaGlyphs.Device
    /** Grid layout (alias of [View]'s 2×2 grid). */
    val GridLayout: RovaGlyph = RovaGlyphs.GridLayout
    /** Video / playable footage. */
    val Video: RovaGlyph = RovaGlyphs.Video
    /** Folder / storage destination. */
    val Folder: RovaGlyph = RovaGlyphs.Folder
    /** Cleanup / sweep. */
    val Cleanup: RovaGlyph = RovaGlyphs.Cleanup
    /** Delete all / clear (distinct from [Delete]; pair with `status = IconStatus.Danger`). */
    val DeleteAll: RovaGlyph = RovaGlyphs.DeleteAll
    /** Privacy / hidden-from-view. */
    val Privacy: RovaGlyph = RovaGlyphs.Privacy
    /** Info / read-only details. */
    val Info: RovaGlyph = RovaGlyphs.Info
    /** Camera access (affirmative onboarding; no slash). */
    val CameraAccess: RovaGlyph = RovaGlyphs.CameraAccess
    /** Microphone access (affirmative onboarding; no slash). */
    val MicAccess: RovaGlyph = RovaGlyphs.MicAccess
    /** Front/back camera flip (duotone). Camera-flip call-sites migrate here in 5b-2…5b-5. */
    val FlipCam: RovaGlyph = RovaGlyphs.FlipCam

    // ── Status concept — the locked status travels WITH the map entry (codex 2026-06-19) ──
    /**
     * Warning STATUS. WarnTriangle is mono, so its single layer is exposed as the [RovaIcon]
     * image vector with the locked [IconStatus.Warning] baked in — a call-site reads `.glyph`
     * and `.status` and cannot forget the amber lock. Was Icons.Default.WarningAmber.
     */
    val WarningStatus = RovaIcon(RovaGlyphs.WarnTriangle.outline, status = IconStatus.Warning)

    /**
     * Recovered STATUS (recovered-clip-verified). RecClipCheck's outline layer carries the
     * locked [IconStatus.Recovered] (→ success green) so a call-site reads `.glyph` and `.status`
     * and cannot forget the lock. Mirrors [WarningStatus]'s form. board `rec_clipcheck`.
     */
    val Recovered = RovaIcon(RovaGlyphs.RecClipCheck.outline, status = IconStatus.Recovered)

    /**
     * Interrupted STATUS (session interrupted / killed). Interrupted's outline layer carries the
     * locked [IconStatus.Interrupted] (→ escalating orange) so the lock travels with the concept.
     * Mirrors [WarningStatus]'s form. board `interrupted`.
     */
    val Interrupted = RovaIcon(RovaGlyphs.Interrupted.outline, status = IconStatus.Interrupted)

    // ── Still stock Material (RovaIcon), authored in a later slice ──
    // "View settings" on a library item shows that recording's read-only capture config — per-item
    // details/metadata, not app preferences → Info (ⓘ), not a gear (owner 2026-06-17).
    val Details = RovaIcon(Icons.Outlined.Info)
    val Play = RovaIcon(RovaGlyphs.Play)
}
