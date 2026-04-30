package com.aritr.rova

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
        // ONLY here, not from RovaApp.onCreate. When the process is cold-
        // started for receiver delivery (a pending TICK or STOP alarm),
        // RovaApp.onCreate runs without a UI surface; running the scan
        // there would race the receiver's terminal write. MainActivity
        // implies a foreground UI moment, which is the intended trigger.
        // triggerRecoveryScanIfNeeded() is idempotent per process via an
        // AtomicBoolean latch (Guard A resets the latch on scan failure).
        (application as RovaApp).triggerRecoveryScanIfNeeded()
        enableEdgeToEdge()
        setContent {
            RovaTheme {
                MainScreen()
            }
        }
    }
}
