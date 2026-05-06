package com.aritr.rova.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Phase 2 Slice 2.1a — Compose surface for a single recovery card.
 *
 * Pure-presentation: takes a [RecoveryCardState] (built by
 * [RecoveryUiStateMapper]) plus a Discard callback. No `RovaApp`
 * reference, no `SessionStore` coupling.
 *
 * Internal beta correction (smoke 2026-05-03): the Merge button is
 * removed for beta — no service-side merge API exists yet and the
 * placeholder snackbar made the UI feel incomplete. Discard is wired
 * for real via the wiring layer. The card now exposes only the
 * Discard affordance.
 *
 * Slice 4 (UI redesign) — softened visual treatment per the accepted
 * mockup. Background is a neutral surface tone (`surfaceVariant`)
 * with a 4 dp `error` left stripe; the title uses a neutral
 * `onSurface` color so the card no longer reads like a full red
 * alert. The destructive `Discard recording` button retains the full
 * `error` background so the consequential action remains
 * unmistakable. Behavior, callbacks, and KDoc invariants are
 * unchanged from Slice 2.1a.
 *
 * `vendorHelpSlot` is rendered only when the underlying state has
 * `showVendorHelpSlot == true` (currently only
 * [RecoveryCardKind.KILLED_BY_SYSTEM]) AND the consumer supplies a
 * non-null slot.
 */
@Composable
fun RecoveryCard(
    state: RecoveryCardState,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlot: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.survivingArtifacts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column {
                        state.survivingArtifacts.forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (state.showVendorHelpSlot && vendorHelpSlot != null) {
                        vendorHelpSlot()
                        Spacer(Modifier.width(8.dp))
                    }
                    // Slice 4 — destructive treatment retained so the
                    // permanent action remains unmistakable on the
                    // softened card.
                    Button(
                        onClick = onDiscard,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.semantics {
                            contentDescription =
                                "${state.discardLabel}. This action is permanent."
                        }
                    ) {
                        Text(state.discardLabel)
                    }
                }
            }
        }
    }
}

/**
 * Renders the (at most one) recovery card from [state] plus a small
 * footer line when [RecoveryUiState.hiddenCount] > 0 so the user
 * sees that older interrupted sessions exist without a stacking red
 * wall. Empty [RecoveryUiState.cards] renders nothing — the consumer
 * can place this composable unconditionally.
 *
 * `onDiscard` receives the visible card's `sessionId` so the wiring
 * layer can route to `RecoveryViewModel.dismiss(sessionId)`.
 *
 * `vendorHelpSlotFor` is invoked with the visible card's `sessionId`;
 * consumers without a vendor helper pass `{ _ -> null }` (or omit;
 * the default is `null`).
 */
@Composable
fun RecoveryCardList(
    state: RecoveryUiState,
    onDiscard: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlotFor: (sessionId: String) -> (@Composable () -> Unit)? = { null }
) {
    if (state.cards.isEmpty() && state.hiddenCount == 0) return
    Column(modifier = modifier.fillMaxWidth()) {
        state.cards.forEachIndexed { index, card ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            RecoveryCard(
                state = card,
                onDiscard = { onDiscard(card.sessionId) },
                vendorHelpSlot = vendorHelpSlotFor(card.sessionId)
            )
        }
        if (state.hiddenCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "+${state.hiddenCount} older interrupted sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
