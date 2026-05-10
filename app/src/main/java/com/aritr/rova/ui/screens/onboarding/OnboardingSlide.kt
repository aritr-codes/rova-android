package com.aritr.rova.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.RovaWarnings

/**
 * Phase 2.6 — body composables for both walkthrough and permission
 * onboarding slides. Two layouts share the same shell (full-bleed
 * column, top spacer, illustration + copy stack, bottom CTA group)
 * but diverge on:
 *  - illustration shape (centered hero glyph for walkthrough,
 *    rounded-square icon tile for permission)
 *  - dots indicator (3 dots for walkthrough, 4 dots for permission)
 *  - CTA pair (single primary on the last walkthrough; primary +
 *    optional skip on permission slides)
 *
 * The illustrations in `mockups/new_uiux/08-onboarding.html` are
 * inline SVG. Reproducing them as Compose drawing scope blows the
 * slice's LOC budget; the production substitutes are Material
 * symbols at hero size with the mockup's primary glow circling them.
 * Phase 7 (polish) is the right slice to upgrade these to bespoke
 * vector drawables.
 *
 * No new design tokens this slice (Phase 2.6 brief). Colors flow
 * through `MaterialTheme.colorScheme` (`primary` = `#5B7FFF`,
 * `error` = `#EF4444`) and existing [RovaTokens] / [RovaWarnings].
 */

private data class WalkthroughCopy(
    val title: String,
    val subtitle: String,
    val illustration: ImageVector,
    val ctaLabel: String
)

private data class PermissionCopy(
    val stepLabel: String,
    val title: String,
    val description: String,
    val illustration: ImageVector,
    val accent: Color,
    val ctaLabel: String,
    val callout: PermissionCallout?,
    val showSkip: Boolean,
    val skipLabel: String
)

private data class PermissionCallout(
    val text: String,
    val severity: Color
)

@Composable
internal fun WalkthroughSlide(
    step: OnboardingStep,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    require(step.isWalkthrough) { "WalkthroughSlide given $step" }
    val copy = walkthroughCopy(step)
    val showSkip = step != OnboardingStep.WALKTHROUGH_3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 26.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, end = 0.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (showSkip) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            HeroIllustration(
                icon = copy.illustration,
                accent = MaterialTheme.colorScheme.primary
            )
        }

        DotsIndicator(
            total = 3,
            activeIndex = step.ordinal - OnboardingStep.WALKTHROUGH_1.ordinal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        )

        Text(
            text = copy.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = copy.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(26.dp))
        PrimaryCta(label = copy.ctaLabel, onClick = onNext)
        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
internal fun PermissionSlide(
    step: OnboardingStep,
    onAllow: () -> Unit,
    onSkip: () -> Unit
) {
    require(step.isPermission) { "PermissionSlide given $step" }
    val copy = permissionCopy(step)
    val activeIndex = step.ordinal - OnboardingStep.PERM_CAMERA.ordinal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(58.dp))
        PermissionIconTile(icon = copy.illustration, accent = copy.accent)
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = copy.stepLabel.uppercase(),
            style = RovaTokens.eyebrow.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = copy.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = copy.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
        copy.callout?.let { callout ->
            Spacer(modifier = Modifier.height(18.dp))
            CalloutCard(text = callout.text, severity = callout.severity)
        }

        Spacer(modifier = Modifier.weight(1f))

        DotsIndicator(
            total = 4,
            activeIndex = activeIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp)
        )

        PrimaryCta(label = copy.ctaLabel, onClick = onAllow)
        if (copy.showSkip) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = RovaTokens.minHitTarget)
            ) {
                Text(
                    text = copy.skipLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun PrimaryCta(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
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
private fun HeroIllustration(icon: ImageVector, accent: Color) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .background(
                    color = accent.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(88.dp)
                )
        )
        Box(
            modifier = Modifier
                .size(132.dp)
                .background(
                    color = accent.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(66.dp)
                )
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = accent.copy(alpha = 0.11f),
                    shape = RoundedCornerShape(36.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.88f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun PermissionIconTile(icon: ImageVector, accent: Color) {
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
            contentDescription = null,
            tint = accent.copy(alpha = 0.90f),
            modifier = Modifier.size(34.dp)
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
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = severity.copy(alpha = 0.78f)
            )
        }
    }
}

private fun walkthroughCopy(step: OnboardingStep): WalkthroughCopy = when (step) {
    OnboardingStep.WALKTHROUGH_1 -> WalkthroughCopy(
        title = "Record on repeat",
        subtitle = "Set a clip duration, a gap, and how many loops. Rova handles the timing automatically.",
        illustration = Icons.Filled.Refresh,
        ctaLabel = "Next →"
    )
    OnboardingStep.WALKTHROUGH_2 -> WalkthroughCopy(
        title = "Walks away with you",
        subtitle = "Screen off, pocket, bag — Rova keeps recording in the background. No babysitting required.",
        illustration = Icons.Filled.PhoneAndroid,
        ctaLabel = "Next →"
    )
    OnboardingStep.WALKTHROUGH_3 -> WalkthroughCopy(
        title = "One session, one file",
        subtitle = "When your loops finish, all clips merge automatically into a single video — ready to share.",
        illustration = Icons.Filled.VideoLibrary,
        ctaLabel = "Get Started →"
    )
    else -> error("walkthroughCopy: $step")
}

@Composable
private fun permissionCopy(step: OnboardingStep): PermissionCopy = when (step) {
    OnboardingStep.PERM_CAMERA -> PermissionCopy(
        stepLabel = "Step 1 of 4",
        title = "Allow Camera access",
        description = "Rova needs your camera to record video. This is required — without it the app cannot function.",
        illustration = Icons.Filled.Videocam,
        accent = MaterialTheme.colorScheme.primary,
        ctaLabel = "Allow Camera",
        callout = null,
        showSkip = false,
        skipLabel = ""
    )
    OnboardingStep.PERM_MIC -> PermissionCopy(
        stepLabel = "Step 2 of 4",
        title = "Allow Microphone",
        description = "Lets Rova record audio alongside your video. Skip this and sessions will be video-only — no sound.",
        illustration = Icons.Filled.Mic,
        accent = Color(0xFF34D399),
        ctaLabel = "Allow Microphone",
        callout = PermissionCallout(
            text = "You can change this any time in Settings → Recording Defaults.",
            severity = RovaWarnings.soft
        ),
        showSkip = true,
        skipLabel = "Skip for now"
    )
    OnboardingStep.PERM_NOTIFS -> PermissionCopy(
        stepLabel = "Step 3 of 4",
        title = "Stay in control",
        description = "A live notification lets you stop or check progress without opening the app — even with the screen off.",
        illustration = Icons.Filled.Notifications,
        accent = RovaWarnings.soft,
        ctaLabel = "Allow Notifications",
        callout = null,
        showSkip = true,
        skipLabel = "Skip"
    )
    OnboardingStep.PERM_ALARM -> PermissionCopy(
        stepLabel = "Step 4 of 4",
        title = "Precise timing",
        description = "Rova uses exact alarms to switch clips at exactly the right second. Without this, timing will drift and loops won't line up.",
        illustration = Icons.Filled.Alarm,
        accent = MaterialTheme.colorScheme.primary,
        ctaLabel = "Open Settings to Grant",
        callout = PermissionCallout(
            text = "This opens Android's Alarms & Reminders settings. Grant the toggle and come back.",
            severity = RovaWarnings.hard
        ),
        showSkip = false,
        skipLabel = ""
    )
    else -> error("permissionCopy: $step")
}
