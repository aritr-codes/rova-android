package com.aritr.rova.ui.signals

import androidx.camera.core.CameraState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3.5 — pure-JVM tests for [CameraStateSignal].
 *
 * No constructor seam (no SDK gate, no system-service read): the signal
 * is fed by the recording service's `cameraState` observer. Tests drive
 * [CameraStateSignal.onCameraState] with raw CameraX error-code ints —
 * the `CameraState.ERROR_*` constants are compile-time `static final
 * int`, available on the JVM test classpath (camera-core is an
 * `implementation` dep, which `testImplementation` extends) without a
 * device — and assert the mapped [CameraSignalState].
 *
 * runBlocking + Unconfined collector + yield + cancelAndJoin — same
 * collection pattern as [ExactAlarmSignalTest] / [PowerSignalTest]
 * (no Turbine, no kotlinx-coroutines-test).
 */
class CameraStateSignalTest {

    @Test fun `initial state is UNKNOWN before any feed`() {
        assertEquals(CameraSignalState.UNKNOWN, CameraStateSignal().state.value)
    }

    @Test fun `null error code maps to OK`() {
        val signal = CameraStateSignal()
        signal.onCameraState(null)
        assertEquals(CameraSignalState.OK, signal.state.value)
    }

    @Test fun `ERROR_CAMERA_IN_USE maps to IN_USE`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_CAMERA_IN_USE)
        assertEquals(CameraSignalState.IN_USE, signal.state.value)
    }

    @Test fun `ERROR_MAX_CAMERAS_IN_USE maps to IN_USE`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_MAX_CAMERAS_IN_USE)
        assertEquals(CameraSignalState.IN_USE, signal.state.value)
    }

    @Test fun `ERROR_CAMERA_DISABLED maps to DISABLED`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_CAMERA_DISABLED)
        assertEquals(CameraSignalState.DISABLED, signal.state.value)
    }

    @Test fun `ERROR_DO_NOT_DISTURB_MODE_ENABLED maps to DISABLED`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED)
        assertEquals(CameraSignalState.DISABLED, signal.state.value)
    }

    @Test fun `ERROR_OTHER_RECOVERABLE_ERROR maps to OTHER_ERROR`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_OTHER_RECOVERABLE_ERROR)
        assertEquals(CameraSignalState.OTHER_ERROR, signal.state.value)
    }

    @Test fun `ERROR_STREAM_CONFIG maps to OTHER_ERROR`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_STREAM_CONFIG)
        assertEquals(CameraSignalState.OTHER_ERROR, signal.state.value)
    }

    @Test fun `ERROR_CAMERA_FATAL_ERROR maps to OTHER_ERROR`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_CAMERA_FATAL_ERROR)
        assertEquals(CameraSignalState.OTHER_ERROR, signal.state.value)
    }

    @Test fun `unknown error code maps to OTHER_ERROR defensively`() {
        val signal = CameraStateSignal()
        signal.onCameraState(99999)
        assertEquals(CameraSignalState.OTHER_ERROR, signal.state.value)
    }

    @Test fun `recovery transition OK to IN_USE to OK`() {
        val signal = CameraStateSignal()
        signal.onCameraState(null)
        assertEquals(CameraSignalState.OK, signal.state.value)
        signal.onCameraState(CameraState.ERROR_CAMERA_IN_USE)
        assertEquals(CameraSignalState.IN_USE, signal.state.value)
        signal.onCameraState(null)
        assertEquals(CameraSignalState.OK, signal.state.value)
    }

    @Test fun `onCameraUnbound resets to UNKNOWN from OK`() {
        val signal = CameraStateSignal()
        signal.onCameraState(null)
        assertEquals(CameraSignalState.OK, signal.state.value)
        signal.onCameraUnbound()
        assertEquals(CameraSignalState.UNKNOWN, signal.state.value)
    }

    @Test fun `onCameraUnbound resets to UNKNOWN from IN_USE`() {
        val signal = CameraStateSignal()
        signal.onCameraState(CameraState.ERROR_CAMERA_IN_USE)
        assertEquals(CameraSignalState.IN_USE, signal.state.value)
        signal.onCameraUnbound()
        assertEquals(CameraSignalState.UNKNOWN, signal.state.value)
    }

    @Test fun `idempotent feed emits initial UNKNOWN then a single OK`() = runBlocking {
        val signal = CameraStateSignal()
        val emissions = mutableListOf<CameraSignalState>()
        val job = launch(Dispatchers.Unconfined) {
            signal.state.collect { emissions += it }
        }
        // Collector replays the current value (UNKNOWN) first; the three
        // identical OK feeds then dedup (MutableStateFlow equality) to a
        // single OK emission.
        signal.onCameraState(null)
        signal.onCameraState(null)
        signal.onCameraState(null)
        yield()
        job.cancelAndJoin()
        assertEquals(listOf(CameraSignalState.UNKNOWN, CameraSignalState.OK), emissions)
    }
}
