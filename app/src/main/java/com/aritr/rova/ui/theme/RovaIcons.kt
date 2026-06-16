package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.screens.RecordChromeIcons

/**
 * Canonical concept→glyph map (ADR-0031 §2/§5). One concept → exactly one glyph, everywhere — a single
 * edit re-points a concept. Bespoke vectors stay in [RecordChromeIcons] until P1 folds them into a
 * `RovaGlyphs` home; stock Material glyphs are aliased here. **No vector is authored in P0.**
 *
 * `status` is non-null only for STATUS concepts — those tint through [IconStatus] (locked RovaSemantics),
 * never an identity role. Keeps Warning-the-status distinct from Notifications-the-setting.
 */
data class RovaIcon(val glyph: ImageVector, val status: IconStatus? = null)

object RovaIcons {
    // ── Navigation / brand (existing bespoke — kept as canonical references; P1 redraws to RovaGlyphs) ──
    val Library = RovaIcon(RecordChromeIcons.library)
    val Settings = RovaIcon(RecordChromeIcons.settings)
    val Play = RovaIcon(RecordChromeIcons.fabPlay)

    // ── Stock actions (state-free) ──
    val Sort = RovaIcon(Icons.AutoMirrored.Filled.Sort)
    val View = RovaIcon(Icons.Default.GridView)            // distinct from Settings (gear) and Sort (bars)

    // ── Status vs setting split (spec §7.3) ──
    val WarningStatus = RovaIcon(Icons.Default.WarningAmber, status = IconStatus.Warning)  // locked amber
    val NotificationsSetting = RovaIcon(Icons.Default.Notifications)                        // bell, a setting
}
