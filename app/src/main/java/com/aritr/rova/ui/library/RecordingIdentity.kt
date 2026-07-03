package com.aritr.rova.ui.library

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Canonical recording identity shared by the Library and the Player so a saved playback
 * position (and favorite/title) cannot drift between the two sides.
 *
 * sessionId-canonical (codex-reviewed 2026-06-22): keying by sessionId makes the playable-URI
 * tier (content:// vs _DATA path vs SAF docUri vs vaultfile://) irrelevant to identity.
 * See docs/superpowers/specs/2026-06-22-player-resume-identity-seam-design.md.
 */
object RecordingIdentity {

    /** Session-level metadata key (favorite, customTitle, lastPlayedAt, positionsBySide). */
    fun sessionKey(sessionId: String): String = "session:$sessionId"

    /** True for canonical session keys ("session:<id>") — the stableKey shape of aggregated session rows. */
    fun isSessionKey(key: String): Boolean = key.startsWith("session:")

    /** Pre-seam stable key a file/SAF row may still be stored under, for dual-read fallback. */
    fun legacyKey(absolutePath: String?, docUri: String?): String? = absolutePath ?: docUri

    /** Per-side playback-position slot inside the session entry. "" = single; side name for P+L. */
    fun sideSlot(side: VideoSide?): String = side?.name ?: ""

    data class MetaKey(val canonical: String, val legacy: String?)

    /**
     * Manifest-backed (sessionId != null): canonical = sessionKey, legacy = path/docUri grace alias.
     * Sessionless import: canonical = legacy key, legacy = null (never auto-converted).
     */
    fun forItem(sessionId: String?, absolutePath: String?, docUri: String?): MetaKey {
        val legacy = legacyKey(absolutePath, docUri)
        return if (sessionId != null) {
            MetaKey(canonical = sessionKey(sessionId), legacy = legacy)
        } else {
            // Sessionless: legacy IS the canonical; nothing to migrate. legacyKey may be null only
            // if a row had neither file, docUri, nor sessionId — not a real row, but keep total.
            MetaKey(canonical = legacy ?: "", legacy = null)
        }
    }
}
