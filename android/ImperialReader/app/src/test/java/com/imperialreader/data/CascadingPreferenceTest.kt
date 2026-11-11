package com.imperialreader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadingPreferenceTest {
    @Test
    fun enablesOnlyOnNewInstallWithoutPriorData() {
        assertTrue(defaultCascadingAnimations(false, false))
        assertFalse(defaultCascadingAnimations(true, false))
        assertFalse(defaultCascadingAnimations(false, true))
        assertFalse(defaultCascadingAnimations(true, true))
    }
}
