package com.aritr.rova

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.ui.LocalSecureFlagController
import com.aritr.rova.ui.MainScreen
import com.aritr.rova.ui.SecureFlagController
import com.aritr.rova.ui.isPinnedDarkRoute
import com.aritr.rova.ui.locale.LocaleApplier
import com.aritr.rova.ui.screens.SettingsViewModel
import com.aritr.rova.ui.theme.RovaTheme
import com.aritr.rova.ui.theme.resolvePalette

class MainActivity : FragmentActivity() {

    // i18n Phase B (ADR-0023) — API 24–32 locale backport. On those API levels
    // there is no LocaleManager, so the persisted tag is applied here, before
    // the Compose tree resolves resources. No-op on API 33+ (LocaleManager
    // already wrapped newBase) and when the tag is null (system default).
    override fun attachBaseContext(newBase: Context) {
        val tag = RovaSettings(newBase).localeTag
        super.attachBaseContext(LocaleApplier.wrapContext(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ADR 0005 §"Scan Trigger Boundary" — recovery scan is triggered
        // ONLY here, not from RovaApp.onCreate.
        (application as RovaApp).triggerRecoveryScanIfNeeded()
        enableEdgeToEdge()
        val initialTab = readInitialTab(intent)
        setContent {
            // B2 — theme root. The activity-scoped SettingsViewModel is the
            // single owner of themeMode; collecting it ABOVE RovaTheme means a
            // picker change recomposes here and re-themes the whole tree with
            // no Activity recreate. The same instance is passed into MainScreen.
            val settingsViewModel: SettingsViewModel = viewModel()
            // ADR-0028 — theme is now driven by ThemeSelection (flat-12 + Follow-
            // System). Resolve to a concrete palette, then derive dark/light from
            // it (Aurora & all dark palettes → dark scheme; Daylight → light;
            // Follow-System → Aurora/Daylight by the OS flag). This reproduces the
            // prior dark/light behavior exactly — no rendered color change in PR1.
            val themeSelection by settingsViewModel.themeSelection.collectAsStateWithLifecycle()
            val palette = resolvePalette(themeSelection, isSystemInDarkTheme())
            val dark = !palette.isLight
            // navController is hoisted here so the current route can drive
            // system-bar icon polarity: pinned-dark screens (viewfinder/player/
            // onboarding) always need light icons; chrome follows the theme.
            val navController = rememberNavController()
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            val lightBars = if (isPinnedDarkRoute(currentRoute)) false else !dark
            // B5 / ADR-0025 — single ref-counted owner of this window's
            // FLAG_SECURE. Vault list and vault player both secure the window
            // and overlap during the nav transition; ref-counting keeps the
            // flag on while ANY secure screen is active so the exiting screen's
            // late onDispose can't wipe the entering screen's flag. remember
            // keeps one stable instance bound to this single Activity window.
            val secureController = remember {
                SecureFlagController(
                    onFirstAcquire = { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                    onLastRelease = { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                )
            }
            RovaTheme(darkTheme = dark, lightStatusBarIcons = lightBars, palette = palette) {
                CompositionLocalProvider(LocalSecureFlagController provides secureController) {
                    MainScreen(
                        initialTab = initialTab,
                        settingsViewModel = settingsViewModel,
                        navController = navController,
                    )
                }
            }
        }
        handleNotificationAction(intent)
    }

    override fun onDestroy() {
        // perf/player-lifecycle — contract condition 1 (player ceases to be
        // reusable): the app's UI is going away for real. isFinishing gates
        // out config-change recreates, which must keep the parked player
        // warm. Runs on main, satisfying PlayerEngine's threading contract.
        if (isFinishing) (application as RovaApp).playerEngine.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tab re-selection on subsequent launches (e.g., MergeComplete tap
        // while MainActivity is already in the stack) is handled by
        // MainScreen recomposing — we intentionally do not call setContent
        // again. If a future redesign needs runtime tab selection, lift
        // initialTab into a StateFlow and observe.
        handleNotificationAction(intent)
    }

    /**
     * M5 §5 — routes notification action intents to [RovaApp] thin
     * wrappers. Called from both [onCreate] and [onNewIntent] so both
     * cold-start and warm-resume taps are handled.
     */
    private fun handleNotificationAction(intent: Intent?) {
        when (intent?.action) {
            com.aritr.rova.service.RovaStopReceiver.ACTION_STOP -> {
                (application as RovaApp).requestUserStopIfRunning(this)
            }
            com.aritr.rova.service.RovaRecordingService.ACTION_SHARE_RECORDING -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (sessionId != null) (application as RovaApp).shareRecording(this, sessionId)
            }
            ACTION_SCHEDULE_AUTO_ARM -> {
                // ADR-0027 — the user tapped the window-open notification. The
                // Activity is now foregrounded, satisfying the camera-FGS
                // isAppVisible gate; start the scheduled recording here (the one
                // legal camera-start site). MainActivity is exported, so verify
                // the single-use nonce the receiver wrote before starting — an
                // external app can't forge it. Clear on use (rotate).
                val settings = RovaSettings(this)
                val expected = settings.scheduleStartNonce
                val supplied = intent.getStringExtra(EXTRA_SCHEDULE_NONCE)
                if (expected != null && expected == supplied) {
                    settings.scheduleStartNonce = null
                    (application as RovaApp).startScheduledRecording(this)
                }
            }
        }
    }

    private fun readInitialTab(intent: Intent?): InitialTab {
        val target = intent?.getStringExtra(EXTRA_TARGET_TAB) ?: return InitialTab.DEFAULT
        return when (target) {
            TAB_HISTORY -> InitialTab.HISTORY
            else -> InitialTab.DEFAULT
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.aritr.rova.EXTRA_SESSION_ID"
        const val EXTRA_TARGET_TAB = "com.aritr.rova.EXTRA_TARGET_TAB"
        const val TAB_HISTORY = "history"

        /** ADR-0027 — window-open notification tap → start the scheduled recording. */
        const val ACTION_SCHEDULE_AUTO_ARM = "com.aritr.rova.action.SCHEDULE_AUTO_ARM"

        /** ADR-0027 — single-use nonce extra anti-forgery (see RovaSettings.scheduleStartNonce). */
        const val EXTRA_SCHEDULE_NONCE = "com.aritr.rova.EXTRA_SCHEDULE_NONCE"
    }
}

enum class InitialTab { DEFAULT, HISTORY }
