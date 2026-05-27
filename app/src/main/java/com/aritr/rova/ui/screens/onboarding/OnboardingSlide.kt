package com.aritr.rova.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R

/**
 * M4 (2026-05-27) Onboarding redesign — Compose slide bodies.
 *
 * [WalkthroughSlide] handles `WALKTHROUGH_1` and `WALKTHROUGH_2`
 * (single dynamic copy lookup). [CameraRationaleSlide] is the
 * single permission slide and renders Camera-only rationale text +
 * "Allow Camera" CTA + "Not now" link.
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(26.dp))
        PrimaryCta(label = stringResource(ctaRes) + " →", onClick = onNext)
        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
internal fun CameraRationaleSlide(
    onAllow: () -> Unit,
    onNotNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(96.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(40.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                OnboardingCamera(
                    contentDesc = stringResource(R.string.onboarding_illustration_camera)
                )
            }
        }
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.onboarding_camera_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.94f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_camera_body),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        PrimaryCta(
            label = stringResource(R.string.onboarding_camera_allow),
            onClick = onAllow
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onNotNow,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_camera_not_now),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
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
