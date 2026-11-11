package com.imperialreader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTransitionsTest {
    @Test
    fun transitionDurationIsBetweenTwoAndThreeSeconds() {
        assertTrue(LibraryTransitions.DURATION_MS in 2_000L..3_000L)
    }

    @Test
    fun mapsAllSettingsToTheirEffects() {
        assertTrue(LibraryTransitionStyle.fromPreference("fade").fade)
        assertFalse(LibraryTransitionStyle.fromPreference("fade").slide)
        assertFalse(LibraryTransitionStyle.fromPreference("slide").fade)
        assertTrue(LibraryTransitionStyle.fromPreference("slide").slide)
        assertTrue(LibraryTransitionStyle.fromPreference("fade_slide").fade)
        assertTrue(LibraryTransitionStyle.fromPreference("fade_slide").slide)
        assertEquals(LibraryTransitionStyle.CASCADE, LibraryTransitionStyle.fromPreference("cascade"))
        assertEquals(LibraryTransitionStyle.FADE, LibraryTransitionStyle.fromPreference("unknown"))
    }

    @Test
    fun directionFollowsTabOrder() {
        assertEquals(1, transitionDirection(0, 2))
        assertEquals(-1, transitionDirection(2, 0))
        assertEquals(1, transitionDirection(1, 1))
    }
}
