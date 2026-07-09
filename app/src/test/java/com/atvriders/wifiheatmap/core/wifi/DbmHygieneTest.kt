package com.atvriders.wifiheatmap.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DbmHygieneTest {

    @Test
    fun typicalRssiPassesThroughUnchanged() {
        assertEquals(-55, DbmHygiene.sanitize(-55))
    }

    @Test
    fun boundaryValuesAreValid() {
        assertEquals(-100, DbmHygiene.sanitize(-100))
        assertEquals(-10, DbmHygiene.sanitize(-10))
    }

    @Test
    fun justBelowFloorIsRejected() {
        assertNull(DbmHygiene.sanitize(-101))
    }

    @Test
    fun invalidSentinelIsRejected() {
        assertNull(DbmHygiene.sanitize(-127))
    }

    @Test
    fun zeroAndPositiveGarbageAreRejected() {
        assertNull(DbmHygiene.sanitize(0))
        assertNull(DbmHygiene.sanitize(23))
    }
}
