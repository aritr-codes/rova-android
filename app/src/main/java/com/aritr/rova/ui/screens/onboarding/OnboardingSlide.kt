package com.aritr.rova.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.RovaWarnings

/**
 * M4 (2026-05-27) Onboarding redesign — Compose slide bodies.
 *
 * [WalkthroughSlide] handles `WALKTHROUGH_1` and `WALKTHROUGH_2`
 * (single dynamic copy lookup). [PermissionSlide] renders the
 * Camera / Microphone / Notifications rationale cards (generic,
 * driven by step).
 *
 * Mockup contract: `mockups/new_uiux/08b-onboarding.html`. CTA height
 * 52 dp (WCAG 2.2 ≥48 dp). All copy via `stringResource` EXCEPT the
 * walkthrough-CTA trailing arrow `" →"` glued at the composable site —
 * an explicitly-deferred i18n nit (plan §Out-of-scope #8: RTL would
 * want this flipped or omitted). Moving the arrow into the string
 * resource is the future i18n polish item.
 */

@Composable
internal fun WalkthroughSlide(
    step: OnboardingStep,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    require(step.isWalkthrough) { "WalkthroughSlide given $step" }

    val titleRes: Int
    val bodyRes: Int
    val illustration: @Composable () -> Unit
    val ctaRes: Int
    when (step) {
        OnboardingStep.WALKTHROUGH_1 -> {
            titleRes = R.string.onboarding_walkthrough_1_title
            bodyRes = R.string.onboarding_walkthrough_1_body
            illustration = {
                OnboardingClockOrbit(
                    contentDesc = stringResource(R.string.onboarding_illustration_clock_orbit)
                )
            }
            ctaRes = R.string.onboarding_next
        }
        OnboardingStep.WALKTHROUGH_2 -> {
            titleRes = R.string.onboarding_walkthrough_2_title
            bodyRes = R.string.onboarding_walkthrough_2_body
            illustration = {
                OnboardingClipMerge(
                    contentDesc = stringResource(R.string.onboarding_illustration_clip_merge)
                )
            }
            ctaRes = R.string.onboarding_continue
        }
        else -> error("WalkthroughSlide: unreachable for $step")
    }
    val activeIndex = step.ordinal - OnboardingStep.WALKTHROUGH_1.ordinal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Owner feedback 2026-05-27 — MainActivity is edge-to-edge, so
            // content extends behind status + nav bars. safeDrawing accounts
            // for both system bars + display cutout. Pairs with the immersive
            // sticky controller in OnboardingScreen — when bars are hidden the
            // insets are zero (no extra padding); when the user swipes-from-edge
            // and bars transiently reappear, the content shifts to clear them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 26.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    style = MaterialTheme.typography.labelMedium,
                    // WCAG 2.2 AA SC 1.4.3 (ADR-0020, ONB-01): drop the 0.45α
                    // dimming — full onSurfaceVariant clears 4.5:1.
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            illustration()
        }

        DotsIndicator(
            total = 2,
            activeIndex = activeIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        )

        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            // WCAG 2.2 AA SC 1.4.3 (ADR-0020, ONB-02): 0.65α body was ~4:1;
            // onSurface @0.80α clears 4.5:1 for primary body copy.
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(26.dp))
        PrimaryCta(label = stringResource(ctaRes) + " →", onClick = onNext)
        Spacer(modifier = Modifier.height(36.dp))
    }
}

/**
 * 2026-05-31 — generic permission rationale card for PERM_CAMERA / PERM_MIC /
 * PERM_NOTIFS (replaces the M4 camera-only CameraRationaleSlide). Reuses the
 * file's [PrimaryCta] + [DotsIndicator]. [permIndex] / [permTotal] are the
 * card's position among the device's visible permission steps (so the eyebrow
 * + dots read "Step 2 of 3" on API 33+, "Step 2 of 2" below 33).
 *
 * Every card has Allow + "Skip for now"; skipping advances. Camera carries a
 * "Required" eyebrow but is still skippable here — it is enforced at the
 * WarningCenter Start-gate, not by trapping the user (spec §Decisions).
 * WCAG 2.2 AA (ADR-0020): title is a heading; CTA ≥48 dp; skip ≥48 dp.
 */
@Composable
internal fun PermissionSlide(
    step: OnboardingStep,
    permIndex: Int,
    permTotal: Int,
    onAllow: () -> Unit,
    onSkip: () -> Unit
) {
    require(step.isPermission) { "PermissionSlide given $step" }

    val titleRes: Int
    val bodyRes: Int
    val allowRes: Int
    val iconDescRes: Int
    val icon: ImageVector
    val accent: Color
    val severityWordRes: Int
    val calloutRes: Int?
    val calloutSeverity: Color
    when (step) {
        OnboardingStep.PERM_CAMERA -> {
            titleRes = R.string.onboarding_camera_title
            bodyRes = R.string.onboarding_camera_body
            allowRes = R.string.onboarding_camera_allow
            iconDescRes = R.string.onboarding_illustration_camera
            icon = Icons.Filled.Videocam
            accent = MaterialTheme.colorScheme.primary
            severityWordRes = R.string.onboarding_perm_required
            calloutRes = null
            calloutSeverity = RovaWarnings.soft
        }
        OnboardingStep.PERM_MIC -> {
            titleRes = R.string.onboarding_mic_title
            bodyRes = R.string.onboarding_mic_body
            allowRes = R.string.onboarding_mic_allow
            iconDescRes = R.string.onboarding_illustration_mic
            icon = Icons.Filled.Mic
            accent = Color(0xFF34D399)
            severityWordRes = R.string.onboarding_perm_optional
            calloutRes = R.string.onboarding_mic_callout
            calloutSeverity = RovaWarnings.soft
        }
        OnboardingStep.PERM_NOTIFS -> {
            titleRes = R.string.onboarding_notifs_title
            bodyRes = R.string.onboarding_notifs_body
            allowRes = R.string.onboarding_notifs_allow
            iconDescRes = R.string.onboarding_illustration_notifications
            icon = Icons.Filled.Notifications
            accent = RovaWarnings.soft
            severityWordRes = R.string.onboarding_perm_recommended
            calloutRes = null
            calloutSeverity = RovaWarnings.soft
        }
        else -> error("PermissionSlide: unreachable for $step")
    }

    val eyebrow = (stringResource(R.string.onboarding_perm_step_label, permIndex + 1, permTotal) +
        " · " + stringResource(severityWordRes)).uppercase()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(58.dp))
        PermissionIconTile(icon = icon, accent = accent, contentDesc = stringResource(iconDescRes))
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = eyebrow,
            style = RovaTokens.eyebrow.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
        if (calloutRes != null) {
            Spacer(modifier = Modifier.height(18.dp))
            CalloutCard(text = stringResource(calloutRes), severity = calloutSeverity)
        }

        Spacer(modifier = Modifier.weight(1f))

        DotsIndicator(
            total = permTotal,
            activeIndex = permIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp)
        )

        PrimaryCta(label = stringResource(allowRes), onClick = onAllow)
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RovaTokens.minHitTarget)
        ) {
            Text(
                text = stringResource(R.string.onboarding_perm_skip),
                style = MaterialTheme.typography.labelMedium,
                // WCAG 2.2 AA SC 1.4.3 (ADR-0020, ONB-03): drop 0.45α dimming
                // — full onSurfaceVariant clears 4.5:1.
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun PermissionIconTile(icon: ImageVector, accent: Color, contentDesc: String) {
    Box(
        modifier = Modifier
            .size(70.dp)
            .background(
                color = accent.copy(alpha = 0.09f),
                shape = RoundedCornerShape(22.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = accent.copy(alpha = 0.90f),
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun CalloutCard(text: String, severity: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = severity.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp))
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = severity.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = 1.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = severity.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun PrimaryCta(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        )
    }
}

@Composable
private fun DotsIndicator(
    total: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val isActive = i == activeIndex
            val isDone = i < activeIndex
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (isActive) 20.dp else 5.dp)
                    .background(
                        color = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                        },
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
