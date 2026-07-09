package com.atvriders.wifiheatmap.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {

    @Test
    fun imperialCountriesDefaultToFeet() {
        assertEquals(DistanceUnit.FEET, SettingsStore.defaultDistanceUnitForCountry("US"))
        assertEquals(DistanceUnit.FEET, SettingsStore.defaultDistanceUnitForCountry("us"))
        assertEquals(DistanceUnit.FEET, SettingsStore.defaultDistanceUnitForCountry("LR"))
        assertEquals(DistanceUnit.FEET, SettingsStore.defaultDistanceUnitForCountry("MM"))
    }

    @Test
    fun everyoneElseDefaultsToMeters() {
        for (c in listOf("CA", "GB", "DE", "JP", "AU", "")) {
            assertEquals(DistanceUnit.METERS, SettingsStore.defaultDistanceUnitForCountry(c))
        }
    }
}
