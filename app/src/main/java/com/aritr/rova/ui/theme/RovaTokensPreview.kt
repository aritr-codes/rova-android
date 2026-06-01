package com.aritr.rova.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Phase 2.1A — preview-only verification surface for the token foundation.
 *
 * This is **not** a screen. It exercises a handful of [RovaTokens] and
 * [RovaWarnings] entries side-by-side under both color schemes so the
 * Phase 2.1A PR has a visual proof point that the tokens render
 * coherently. The companion of this file is the design contract in
 * `docs/UI_DESIGN_TOKENS.md` §2 — anything visible below should trace
 * back to a row in that contract.
 *
 * No production composable should `@file:OptIn`-import this file.
 */
@Composable
private fun TokenSamplerCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shape = RovaTokens.pill,
        modifier = Modifier
            .padding(RovaTokens.screenEdgeMargin)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RovaTokens.pill
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status dot at the documented size, tinted with a warning severity
            // (`RovaWarnings.hard`). Demonstrates a screen-local domain token
            // that is *not* in `MaterialTheme.colorScheme`.
            Box(
                modifier = Modifier
                    .size(RovaTokens.statusDotSize)
                    .background(color = RovaWarnings.hard, shape = CircleShape)
            )
            // statusPillLabel exercises tabular-nums on a short label.
            Text(text = "REC", style = RovaTokens.statusPillLabel) // i18n-opt-out: preview-only sample data, not a shipped screen

            Spacer(Modifier.width(8.dp))

            // 5-cell record summary card uses cellValue + cellKey as a pair;
            // a single value/key pair is enough to show both styles.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "10s", style = RovaTokens.cellValue) // i18n-opt-out: preview-only sample data, not a shipped screen
                Text(
                    text = "DURATION",
                    style = RovaTokens.cellKey,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Eyebrow style — uppercase applied at the call site, not the token.
            Text(
                text = "Phase 2.1A".uppercase(),
                style = RovaTokens.eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TokenSamplerScaffold() {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier.padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TokenSamplerCard()

            // Severity strip — confirms all four `RovaWarnings` entries
            // resolve to a Color and pair with the documented 12% fill /
            // 85% foreground pattern.
            Row(
                modifier = Modifier.padding(horizontal = RovaTokens.screenEdgeMargin),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SeverityChip(label = "HARD", tint = RovaWarnings.hard)
                SeverityChip(label = "SOFT", tint = RovaWarnings.soft)
                SeverityChip(label = "ADV", tint = RovaWarnings.advisory)
                SeverityChip(label = "ESC", tint = RovaWarnings.escalating)
            }

            Spacer(Modifier.height(RovaTokens.minHitTarget))
        }
    }
}

@Composable
private fun SeverityChip(label: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        contentColor = tint.copy(alpha = 0.85f),
        shape = RovaTokens.pill
    ) {
        Text(
            text = label,
            style = RovaTokens.statusPillLabel,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Preview(
    name = "Tokens · Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(
    name = "Tokens · Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun RovaTokensPreview() {
    RovaTheme {
        TokenSamplerScaffold()
    }
}
