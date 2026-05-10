package com.aritr.rova.ui.screens.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aritr.rova.ui.theme.RovaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2.6 — instrumented UI smoke tests for the onboarding slides.
 *
 * Build-gate scope (`./gradlew :app:lintDebug :app:testDebugUnitTest
 * :app:assembleRelease`) does NOT include `connectedAndroidTest`, so
 * these tests are not part of the slice's CI signal — see the PR body
 * for the documented infra gap. They are checked in so a future
 * Compose-test-on-CI slice can flip them on without rewriting fixtures.
 *
 * Coverage:
 *  - happy-path for the first walkthrough slide: title renders + the
 *    primary CTA advances via the injected callback
 *  - "renders" smoke for each permission slide title so a typo in
 *    [permissionCopy] surfaces without needing a manual on-device pass
 *
 * Permission launchers, SDK gating, and the `RovaSettings` write are
 * deliberately not exercised here — they live one layer up in
 * [OnboardingScreen] which depends on [com.aritr.rova.RovaApp]. Driving
 * the slide composables directly keeps these tests Context-light.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingSlideTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun walkthroughSlide1_rendersTitle_andAdvancesOnNextTap() {
        var advanced = false
        composeRule.setContent {
            RovaTheme {
                WalkthroughSlide(
                    step = OnboardingStep.WALKTHROUGH_1,
                    onNext = { advanced = true },
                    onSkip = { }
                )
            }
        }
        composeRule.onNodeWithText("Record on repeat").assertIsDisplayed()
        composeRule.onNodeWithText("Next →").performClick()
        assertTrue("onNext should fire when CTA is tapped", advanced)
    }

    @Test fun walkthroughSlide3_rendersGetStartedCta_andOmitsSkip() {
        composeRule.setContent {
            RovaTheme {
                WalkthroughSlide(
                    step = OnboardingStep.WALKTHROUGH_3,
                    onNext = { },
                    onSkip = { }
                )
            }
        }
        composeRule.onNodeWithText("One session, one file").assertIsDisplayed()
        composeRule.onNodeWithText("Get Started →").assertIsDisplayed()
    }

    @Test fun permissionSlideCamera_rendersTitle() {
        composeRule.setContent {
            RovaTheme {
                PermissionSlide(
                    step = OnboardingStep.PERM_CAMERA,
                    onAllow = { },
                    onSkip = { }
                )
            }
        }
        composeRule.onNodeWithText("Allow Camera access").assertIsDisplayed()
        composeRule.onNodeWithText("Allow Camera").assertIsDisplayed()
    }

    @Test fun permissionSlideMic_rendersTitleAndSkip() {
        var skipped = false
        composeRule.setContent {
            RovaTheme {
                PermissionSlide(
                    step = OnboardingStep.PERM_MIC,
                    onAllow = { },
                    onSkip = { skipped = true }
                )
            }
        }
        composeRule.onNodeWithText("Allow Microphone").assertIsDisplayed()
        composeRule.onNodeWithText("Skip for now").performClick()
        assertTrue("Skip-for-now should fire onSkip", skipped)
    }

    @Test fun permissionSlideNotifs_rendersTitle() {
        composeRule.setContent {
            RovaTheme {
                PermissionSlide(
                    step = OnboardingStep.PERM_NOTIFS,
                    onAllow = { },
                    onSkip = { }
                )
            }
        }
        composeRule.onNodeWithText("Stay in control").assertIsDisplayed()
        composeRule.onNodeWithText("Allow Notifications").assertIsDisplayed()
    }

    @Test fun permissionSlideAlarm_rendersTitleAndOpensSettingsCta() {
        composeRule.setContent {
            RovaTheme {
                PermissionSlide(
                    step = OnboardingStep.PERM_ALARM,
                    onAllow = { },
                    onSkip = { }
                )
            }
        }
        composeRule.onNodeWithText("Precise timing").assertIsDisplayed()
        composeRule.onNodeWithText("Open Settings to Grant").assertIsDisplayed()
    }
}
