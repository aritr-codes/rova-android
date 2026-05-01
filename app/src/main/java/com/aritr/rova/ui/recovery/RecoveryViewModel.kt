package com.aritr.rova.ui.recovery

import androidx.lifecycle.ViewModel
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.recovery.RecoveryReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Phase 2 Slice 2.1b — view-model that adapts the
 * [com.aritr.rova.RovaApp.recoveryReport] StateFlow into a
 * [RecoveryUiState] for the History screen surface.
 *
 * Plain `ViewModel` (NOT `AndroidViewModel`) by design. Dependencies are
 * constructor-injected so the type stays JVM-unit-testable without
 * pulling in `kotlinx-coroutines-test` or a `Dispatchers.Main` rule.
 *
 * The internal scope is built from [ioDispatcher] rather than
 * [androidx.lifecycle.viewModelScope] for the same reason —
 * `viewModelScope` defaults to `Dispatchers.Main.immediate`, which
 * requires `Dispatchers.setMain` infrastructure to test. Tests pass
 * [Dispatchers.Unconfined] so the upstream `map` runs synchronously on
 * the caller thread; production passes [Dispatchers.IO] so the
 * `loadManifest` disk read does not block the UI.
 *
 * The mapper logic itself (hide / render rules) lives in
 * [RecoveryViewSource] / [RecoveryUiStateMapper] and is not duplicated
 * here. This class only owns the StateFlow lifecycle.
 */
class RecoveryViewModel(
    recoveryReport: StateFlow<RecoveryReport?>,
    loadManifest: (String) -> SessionManifest?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    val uiState: StateFlow<RecoveryUiState> = recoveryReport
        .map { report -> RecoveryViewSource.buildUiState(report, loadManifest) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = RecoveryUiState.Empty,
        )

    override fun onCleared() {
        scope.cancel()
    }
}
