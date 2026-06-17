package com.aritr.rova.ui.vault

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.LocalSecureFlagController
import com.aritr.rova.ui.components.RovaAlertDialog
import com.aritr.rova.ui.screens.LibraryRow
import kotlinx.coroutines.launch

/**
 * B5 / ADR-0025 — the hidden vault list screen.
 *
 * Reuses the History list row ([com.aritr.rova.ui.screens.LibraryRow]) to
 * render [VaultViewModel.items] so the vault visually matches the Library;
 * only the data source (vault-visible manifests) and the chrome differ.
 *
 * Protections wired here per spec:
 *  - `FLAG_SECURE` on the host window for the screen's lifetime so the vault
 *    list cannot be screenshotted or appear in the recents thumbnail.
 *  - Auto-relock: collects [RovaApp.vaultLock]; when `unlocked` flips false
 *    (the app was backgrounded → ON_STOP relock observer), the screen pops
 *    out so the list is never shown in an unlocked-but-relocked state.
 *  - No-lock banner: when no device credential is enrolled the contents are
 *    still hidden from the gallery but the in-app lock is off — surfaced
 *    honestly via [showNoLockWarning].
 *
 * NOT a pinned-dark route (see MainScreen.isPinnedDarkRoute): the vault
 * follows the app theme exactly like History.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onOpenPlayer: (sessionId: String, side: VideoSide?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    showNoLockWarning: Boolean = false,
    viewModel: VaultViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as RovaApp }
    val items by viewModel.items.collectAsStateWithLifecycle()
    val lockState by app.vaultLock.collectAsStateWithLifecycle()

    // FLAG_SECURE for the screen's lifetime — set on enter, released on dispose.
    // Goes through the ref-counted controller (B5 / ADR-0025) so the vault
    // list's delayed onDispose during the vault->player nav transition can't
    // wipe the player's flag.
    val secureFlag = LocalSecureFlagController.current
    DisposableEffect(secureFlag) {
        secureFlag?.acquire()
        onDispose { secureFlag?.release() }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // Auto-relock: when the in-memory lock flips back to locked (app
    // backgrounded via the ON_STOP observer), leave the vault so the list is
    // not visible after the relock. onBack pops the route and fires
    // onLeaveVault at the nav layer.
    LaunchedEffect(lockState.unlocked) {
        if (!lockState.unlocked) {
            onBack()
        }
    }

    val nowMillis = remember(items) { System.currentTimeMillis() }
    val coroutineScope = rememberCoroutineScope()

    // B5 / ADR-0025 (Task 22) — move-OUT confirmation. Holds the sessionId
    // pending a "Move out of vault" action; non-null shows the warning
    // dialog (the recording becomes gallery-visible again).
    var pendingMoveOutSessionId by remember { mutableStateOf<String?>(null) }

    // B5 / ADR-0025 — permanent-delete confirmation. Holds the sessionId
    // pending a "Delete" action; non-null shows the strong irreversible-delete
    // dialog. Offered for ALL vault rows (single-mode and P+L) because
    // discardSession removes the whole session dir.
    var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.vault_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showNoLockWarning) {
                NoLockBanner()
            }
            if (items.isEmpty()) {
                VaultEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(items, key = { it.stableKey }) { item ->
                        // Vault list is view-only (no multi-select): selection
                        // mode stays false and tap opens the player.
                        LibraryRow(
                            item = item,
                            nowMillis = nowMillis,
                            isSelectionMode = false,
                            isSelected = false,
                            onToggleSelection = {},
                            onPlay = {
                                item.sessionId?.let { sid -> onOpenPlayer(sid, item.side) }
                            },
                            onMenuOpen = {
                                item.sessionId?.let { sid -> onOpenPlayer(sid, item.side) }
                            },
                            // B5 / ADR-0025 (Task 22) — move-OUT. Single-mode
                            // only (item.side == null): P+L per-side rows have
                            // per-side vault pointers VaultAndroidOps can't
                            // resolve, so the action is hidden for them. Auth
                            // is already passed inside the unlocked vault.
                            onMenuMoveOutOfVault = if (item.side == null) {
                                item.sessionId?.let { sid -> { pendingMoveOutSessionId = sid } }
                            } else {
                                null
                            },
                            // B5 / ADR-0025 — permanent delete. Offered for ALL
                            // vault rows including P+L: discardSession removes
                            // the whole session dir, so it works for single-mode
                            // and P+L alike. Note: for a P+L session that shows
                            // two per-side rows, deleting either row removes the
                            // whole session — acceptable for v1.
                            onMenuDelete = item.sessionId?.let { sid ->
                                { pendingDeleteSessionId = sid }
                            }
                        )
                    }
                }
            }
        }
    }

    // B5 / ADR-0025 (Task 22) — move-OUT confirmation. Reuses the vault
    // move/share warning string (moving out un-hides the recording).
    pendingMoveOutSessionId?.let { sid ->
        RovaAlertDialog(
            onDismissRequest = { pendingMoveOutSessionId = null },
            title = stringResource(R.string.vault_move_out),
            text = stringResource(R.string.vault_share_leaves_warning),
            confirmText = stringResource(R.string.vault_move_out),
            onConfirm = {
                pendingMoveOutSessionId = null
                coroutineScope.launch { viewModel.moveOutOfVault(sid) }
            },
            dismissText = stringResource(R.string.dialog_cancel),
            onDismiss = { pendingMoveOutSessionId = null },
        )
    }

    // B5 / ADR-0025 — permanent-delete confirmation. STRONG irreversible
    // warning: a vaulted recording's only copy is its app-private vault file
    // (move-in removed the public copy), so deletion cannot be undone. The
    // confirm button is tinted with the error color to signal destruction.
    pendingDeleteSessionId?.let { sid ->
        RovaAlertDialog(
            onDismissRequest = { pendingDeleteSessionId = null },
            title = stringResource(R.string.vault_delete_title),
            text = stringResource(R.string.vault_delete_body),
            confirmText = stringResource(R.string.vault_delete_confirm),
            destructive = true,
            onConfirm = {
                pendingDeleteSessionId = null
                coroutineScope.launch { viewModel.deleteFromVault(sid) }
            },
            dismissText = stringResource(R.string.dialog_cancel),
            onDismiss = { pendingDeleteSessionId = null },
        )
    }
}

@Composable
private fun NoLockBanner() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.vault_no_lock_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun VaultEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.vault_empty_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.vault_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
