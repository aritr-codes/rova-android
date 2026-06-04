package com.aritr.rova

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.ui.MainScreen
import com.aritr.rova.ui.isPinnedDarkRoute
import com.aritr.rova.ui.locale.LocaleApplier
import com.aritr.rova.ui.screens.SettingsViewModel
import com.aritr.rova.ui.theme.RovaTheme
import com.aritr.rova.ui.theme.resolveDarkTheme

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
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())
            // navController is hoisted here so the current route can drive
            // system-bar icon polarity: pinned-dark screens (viewfinder/player/
            // onboarding) always need light icons; chrome follows the theme.
            val navController = rememberNavController()
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            val lightBars = if (isPinnedDarkRoute(currentRoute)) false else !dark
            RovaTheme(darkTheme = dark, lightStatusBarIcons = lightBars) {
                MainScreen(
                    initialTab = initialTab,
                    settingsViewModel = settingsViewModel,
                    navController = navController,
                )
            }
        }
        handleNotificationAction(intent)
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
    }
}

enum class InitialTab { DEFAULT, HISTORY }
