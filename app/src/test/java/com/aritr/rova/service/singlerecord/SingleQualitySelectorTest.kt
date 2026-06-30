package com.aritr.rova.service.singlerecord

import com.aritr.rova.data.QualityPresets
import org.junit.Assert.assertEquals
import org.junit.Test

class SingleQualitySelectorTest {
    @Test fun maps_uhd() = assertEquals(SingleQuality.UHD, SingleQualitySelector.forResolution(QualityPresets.UHD))
    @Test fun maps_fhd() = assertEquals(SingleQuality.FHD, SingleQualitySelector.forResolution(QualityPresets.FHD))
    @Test fun maps_hd() = assertEquals(SingleQuality.HD, SingleQualitySelector.forResolution(QualityPresets.HD))
    @Test fun maps_sd() = assertEquals(SingleQuality.SD, SingleQualitySelector.forResolution(QualityPresets.SD))
    @Test fun unknown_fallsBackToFhd() = assertEquals(SingleQuality.FHD, SingleQualitySelector.forResolution("garbage"))
}
