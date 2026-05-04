package com.aritr.rova.ui.screens

/**
 * Slice 13B — one-shot snackbar payload for the auto-retention
 * cleanup pass that runs from [HistoryViewModel.refresh] when
 * `RovaSettings.autoDeleteEnabled` is on. Carries only the counts;
 * the human-facing copy lives in [RetentionCleanupNotices.message]
 * so the formatter can be JVM-tested without Compose.
 *
 * Emitted only when the cleanup did real work — [deleted] > 0 or
 * [failed] > 0. A [RecordingRetentionCleaner.Result.NoOp] outcome
 * is dropped at the emit site so a refresh that finds the library
 * already trimmed (the common case) does not spam a snackbar.
 */
internal data class RetentionCleanupNotice(
    val deleted: Int,
    val failed: Int
)

/**
 * Slice 13B — pure formatter for [RetentionCleanupNotice]. Returns
 * `null` for a no-op notice so the caller can guard `showSnackbar`
 * without a separate predicate.
 *
 * Copy:
 *  * `deleted > 0`, `failed == 0` ⇒ `Auto-deleted N old recording(s)`
 *  * `failed > 0`                 ⇒ `Auto-delete removed D recording(s); F failed`
 *  * both zero                    ⇒ `null` (caller skips the snackbar)
 *
 * The `(s)` plural form is intentional — the formatter stays a pure
 * string concat with no inflection branching, which keeps the test
 * matrix small and matches the wording the product spec asked for.
 */
internal object RetentionCleanupNotices {

    fun message(notice: RetentionCleanupNotice): String? {
        val deleted = notice.deleted
        val failed = notice.failed
        return when {
            deleted == 0 && failed == 0 -> null
            failed == 0 -> "Auto-deleted $deleted old recording(s)"
            else -> "Auto-delete removed $deleted recording(s); $failed failed"
        }
    }
}
