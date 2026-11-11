package com.imperialreader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CascadeTimingTest {
    @Test
    fun staggerIsOrderedAndBoundedForLongLists() {
        assertEquals(0L, CascadeTiming.delay(0))
        assertEquals(100L, CascadeTiming.delay(1))
        assertEquals(700L, CascadeTiming.delay(7))
        assertEquals(700L, CascadeTiming.delay(999))
        assertEquals(0L, CascadeTiming.delay(-1))
        assertEquals(2000L, CascadeTiming.DURATION_MS)
        assertEquals(2700L, CascadeTiming.DURATION_MS + CascadeTiming.delay(7))
    }
}
