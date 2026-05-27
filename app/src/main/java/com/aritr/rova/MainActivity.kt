package com.aritr.rova

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aritr.rova.ui.MainScreen
import com.aritr.rova.ui.theme.RovaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ADR 0005 §"Scan Trigger Boundary" — recovery scan is triggered
        // ONLY here, not from RovaApp.onCreate.
        (application as RovaApp).triggerRecoveryScanIfNeeded()
        enableEdgeToEdge()
        val initialTab = readInitialTab(intent)
        setContent {
            RovaTheme {
                MainScreen(initialTab = initialTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tab re-selection on subsequent launches (e.g., MergeComplete tap
        // while MainActivity is already in the stack) is handled by
        // MainScreen recomposing — we intentionally do not call setContent
        // again. If a future redesign needs runtime tab selection, lift
        // initialTab into a StateFlow and observe.
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
