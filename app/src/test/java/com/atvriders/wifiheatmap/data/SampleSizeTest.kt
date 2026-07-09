package com.atvriders.wifiheatmap.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleSizeTest {

    @Test
    fun smallImagesAreNotSampled() {
        assertEquals(1, FloorPlanImageStore.sampleSizeFor(1000, 4096))
        assertEquals(1, FloorPlanImageStore.sampleSizeFor(4096, 4096))
    }

    @Test
    fun oversizeImagesHalveUntilTheyFit() {
        assertEquals(2, FloorPlanImageStore.sampleSizeFor(8000, 4096))
        assertEquals(4, FloorPlanImageStore.sampleSizeFor(16000, 4096))
        assertEquals(2, FloorPlanImageStore.sampleSizeFor(4097, 4096))
    }
}
