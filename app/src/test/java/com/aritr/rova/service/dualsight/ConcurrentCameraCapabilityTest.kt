package com.aritr.rova.service.dualsight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCameraCapabilityTest {

    // A combo is a list of lens facings reported as concurrently-bindable together.
    private val FRONT = LensFacing.FRONT
    private val BACK = LensFacing.BACK

    @Test
    fun featureFlagFalse_neverSupported() {
        val combos = listOf(listOf(FRONT, BACK))
        assertFalse(ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasConcurrentFeature = false, combos = combos))
    }

    @Test
    fun noCombos_notSupported() {
        assertFalse(ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasConcurrentFeature = true, combos = emptyList()))
    }

    @Test
    fun frontBackPairInOneCombo_supported() {
        val combos = listOf(listOf(FRONT, BACK))
        assertTrue(ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasConcurrentFeature = true, combos = combos))
    }

    @Test
    fun onlySameDirectionCombos_notSupported() {
        val combos = listOf(listOf(BACK, BACK), listOf(FRONT, FRONT))
        assertFalse(ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasConcurrentFeature = true, combos = combos))
    }

    @Test
    fun frontOnly_notSupported() {
        assertFalse(ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasConcurrentFeature = true, combos = listOf(listOf(FRONT))))
    }

    @Test
    fun pairAcrossSeparateCombos_notSupported() {
        // A front-only combo and a back-only combo do NOT make a concurrent front+back pair;
        // both facings must appear in the SAME combo.
        val combos = listOf(listOf(FRONT), listOf(BACK))
        assertFalse(ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasConcurrentFeature = true, combos = combos))
    }
}
