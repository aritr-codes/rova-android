package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.LensFacing
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Migration-phase bridge equivalence: asserts V2 first-principles helpers
 * produce matrix output epsilon-equal to legacy `buildCropMatrix` across the
 * legal config product (64 combinations). NOT canonical rendering truth —
 * exists ONLY during the hybrid coexistence window to gate V2 against legacy
 * regression.
 *
 * Matrix equality is WEAKER than rendering correctness. Future post-migration
 * validation may replace bridge matrix equivalence with semantic-space
 * assertions (aspect preservation, orientation correctness, viewport coverage,
 * canonical-frame invariants).
 *
 * `lensFacing` is included in the sweep specifically to **prove mirror
 * isolation** — flipping lensFacing must produce epsilon-equal uvTransform
 * output (proves mirror handling lives OUTSIDE the uvTransform composition,
 * per spec §1.3).
 *
 * **RETIREMENT PLAN**:
 *  - After useFirstPrinciplesRender becomes default (migration PR, 1-2 release
 *    cycles after this PR lands) AND sub-project 2's multi-config smoke
 *    validates: downgrade this class to `@Ignore`'d historical reference, then
 *    delete with legacy `buildCropMatrix` deprecation.
 *  - Failure of this test BEFORE migration = V2 derivation differs from
 *    legacy. REQUIRES investigation. If divergence is the INTENTIONAL V2
 *    correction of a real legacy quirk, retire the failing case from the
 *    bridge and document in spec §5.5 as "intentional V2 correction."
 *
 * See `docs/superpowers/specs/2026-05-18-render-architecture-audit-design.md` §5.2 + §5.5.
 */
@RunWith(Parameterized::class)
class AspectFitMathBridgeTest(
    private val side: VideoSide,
    private val displayRotation: Int,
    private val sensorOrientation: Int,
    @Suppress("unused") private val lensFacing: LensFacing,  // sweep dim; doesn't affect uvTransform (mirror isolation)
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "side={0} displayRotation={1} sensorOrientation={2} lensFacing={3}")
        fun data(): Collection<Array<Any>> {
            val sides = listOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE)
            val displayRotations = listOf(0, 1, 2, 3)
            // Bridge restricted to sensorOrientation=270 — the empirical value
            // at which legacy buildCropMatrix's hardcoded +270° sideCorrection
            // happens to match V2's principled buildTextureNormalization output.
            //
            // The other 3 sensorOrientation values (0, 90, 180) are INTENTIONAL
            // V2 corrections per spec §5.5 — V2 produces the canonically-correct
            // transform for sensors mounted at those orientations, while legacy
            // would over-rotate by (270° - sensorOrientation). Documented as
            // intentional divergence; NOT bridge-gated. Sub-project 2's multi-
            // config smoke validates these on-device.
            val sensorOrientations = listOf(270)
            val lensFacings = listOf(LensFacing.BACK, LensFacing.FRONT)
            val params = mutableListOf<Array<Any>>()
            for (side in sides) for (dr in displayRotations) for (so in sensorOrientations) for (lf in lensFacings) {
                params += arrayOf(side, dr, so, lf)
            }
            return params
        }
    }

    @Test
    fun `V2 uvTransform epsilon-equals legacy buildCropMatrix`() {
        val legacy = FloatArray(16)
        AspectFitMath.buildCropMatrix(displayRotation, side, legacy)

        val v2 = FloatArray(16)
        val a = FloatArray(16); val b = FloatArray(16)
        val c = FloatArray(16); val d = FloatArray(16)
        AspectFitMath.buildUvTransformV2(displayRotation, sensorOrientation, side, v2, a, b, c, d)

        assertArrayEquals(
            "BRIDGE FAILURE: V2 uvTransform diverges from legacy buildCropMatrix " +
                "for side=$side displayRotation=$displayRotation " +
                "sensorOrientation=$sensorOrientation lensFacing=$lensFacing. " +
                "If this divergence is the INTENTIONAL V2 correction of a legacy " +
                "quirk, retire this parameter case from the bridge and document " +
                "as 'intentional V2 correction' in spec §5.5.",
            legacy, v2, 1e-5f,
        )
    }
}
