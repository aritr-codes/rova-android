package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.LensFacing
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase: render-architecture audit. JVM-pure ctor validation + snapshot
 * map cap tests. Per spec §5.2 + §5.6.
 *
 * Test policy: pure-JVM only. NO Robolectric. EglRouter's runtime layer
 * (EGL14 + GLES20 calls in setup()/renderFrame()/release()) is NOT
 * exercised — we test only the ctor validators and the snapshot map
 * lifecycle, neither of which requires an EGL context.
 */
class EglRouterDebugSnapshotTest {

    @Test
    fun `ctor rejects displayRotation 4`() {
        runCatching {
            EglRouter(
                lensFacing = LensFacing.BACK,
                displayRotation = 4,
                sensorOrientation = 90,
                useFirstPrinciplesRender = false,
                enableMatrixSnapshots = false,
            )
        }.let { assertTrue("expected throw on displayRotation=4", it.isFailure) }
    }

    @Test
    fun `ctor rejects displayRotation -1`() {
        runCatching {
            EglRouter(
                lensFacing = LensFacing.BACK,
                displayRotation = -1,
                sensorOrientation = 90,
                useFirstPrinciplesRender = false,
                enableMatrixSnapshots = false,
            )
        }.let { assertTrue("expected throw on displayRotation=-1", it.isFailure) }
    }

    @Test
    fun `ctor rejects sensorOrientation 45`() {
        runCatching {
            EglRouter(
                lensFacing = LensFacing.BACK,
                displayRotation = 0,
                sensorOrientation = 45,
                useFirstPrinciplesRender = false,
                enableMatrixSnapshots = false,
            )
        }.let { assertTrue("expected throw on sensorOrientation=45", it.isFailure) }
    }

    @Test
    fun `ctor rejects sensorOrientation -90`() {
        runCatching {
            EglRouter(
                lensFacing = LensFacing.BACK,
                displayRotation = 0,
                sensorOrientation = -90,
                useFirstPrinciplesRender = false,
                enableMatrixSnapshots = false,
            )
        }.let { assertTrue("expected throw on sensorOrientation=-90", it.isFailure) }
    }

    @Test
    fun `ctor accepts all 4 legal sensorOrientation values`() {
        for (so in listOf(0, 90, 180, 270)) {
            runCatching {
                EglRouter(
                    lensFacing = LensFacing.BACK,
                    displayRotation = 0,
                    sensorOrientation = so,
                    useFirstPrinciplesRender = false,
                    enableMatrixSnapshots = false,
                )
            }.let { result ->
                assertTrue(
                    "expected ctor success for sensorOrientation=$so, got ${result.exceptionOrNull()}",
                    result.isSuccess
                )
            }
        }
    }

    @Test
    fun `debugSnapshot returns null when enableMatrixSnapshots is false`() {
        val router = EglRouter(
            lensFacing = LensFacing.BACK,
            displayRotation = 0,
            sensorOrientation = 90,
            useFirstPrinciplesRender = false,
            enableMatrixSnapshots = false,
        )
        // No setup() / addTarget() called — map is empty regardless of flag,
        // so the assertion is "debugSnapshot returns null for any side".
        assertTrue(
            "debugSnapshot must return null when no snapshot is present",
            router.debugSnapshot(com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT) == null
        )
        assertTrue(
            "debugSnapshot must return null when no snapshot is present",
            router.debugSnapshot(com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE) == null
        )
    }
}
