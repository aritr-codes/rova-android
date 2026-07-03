package com.aritr.rova.ui.library

/**
 * Pure helper for Slice-5 focus restore (WCAG 2.2 AA remediation-backlog row 23 subset): maps a
 * launched library row's `stableKey` to its flattened lazy-list item index so the caller can
 * `scrollToItem(index)` before requesting focus.
 *
 * Item order mirrors [LibraryScreen]'s lazy content: index 0 = recovery/warning header, then for
 * each day-group an OPTIONAL header item followed by its row items.
 */
object FocusRestorePolicy {

    /**
     * Lazy-item index of [pendingKey] in the session list: [0] = recovery/warning header, then per
     * group an OPTIONAL day header (headerless flat bucket under LONGEST/LARGEST — flag false) and
     * its rows. Hero slot removed with the hero (PR-B); flat-bucket flag fixes a pre-existing
     * off-by-one (codex plan-review 2026-07-03). Null = not found (row deleted while away).
     */
    fun targetItemIndex(
        pendingKey: String,
        groupRowKeys: List<List<String>>,
        groupHasHeader: List<Boolean>,
    ): Int? {
        if (pendingKey.isBlank()) return null
        var idx = 1 // [0] = recovery/warning header
        groupRowKeys.forEachIndexed { g, rows ->
            if (groupHasHeader.getOrElse(g) { true }) idx++ // day header
            for (key in rows) {
                if (key == pendingKey) return idx
                idx++
            }
        }
        return null
    }

    /**
     * True when a scroll is needed to bring [pendingKey] into view before restoring focus. On the
     * normal player→Library return the saveable [rememberLazyGridState] has already restored the
     * pre-open scroll position, so the opened tile is still among [visibleKeys] — scrolling then is
     * redundant and jump-scrolls the grid away from the user while stalling the main thread
     * (jitter root cause, device-verified 2026-07-01, RZCYA1VBQ2H). Only scroll when the target
     * genuinely isn't on screen (rare: state not restored). Focus is requested either way, so
     * gating the scroll — not the whole restore — keeps focus restore for every input modality
     * (D-pad / Switch Access / keyboard / TalkBack), unlike a touch-exploration-only gate.
     *
     * Partial visibility counts as "on screen" by design (code-review 2026-07-02, PLAUSIBLE): a
     * tile that is only a sliver at the viewport edge is still in [visibleKeys], so this returns
     * false and no manual scroll fires. That is intentional, not a gap. (1) The only way to open
     * the player is to tap/select a tile, which leaves it substantially visible, and the saveable
     * lazy state restores that same offset on the pop — so a sliver-on-restore is practically
     * unreachable. (2) The target tile is focusable (`combinedClickable` delegates a focusable
     * node), and a focusable node requests a minimal bring-into-view on focus gain — the same path
     * the caller's [requestFocus] takes — so any residual sliver self-heals. Widening this to a
     * "more than marginally visible" test would force a full
     * `scrollToItem`-to-top on that edge case — a BIGGER jump than the framework's minimal scroll
     * (jitter regression) — so partial visibility is deliberately left to the focus system.
     */
    fun shouldScroll(pendingKey: String, visibleKeys: Collection<String>): Boolean =
        pendingKey !in visibleKeys
}
