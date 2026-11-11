package com.imperialreader.ui.theme

import com.imperialreader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppColorSchemeTest {
    @Test
    fun amoledIsAnAdditionalPersistableScheme() {
        assertEquals("AMOLED Black", AppColorScheme.AMOLED_BLACK.label)
        assertEquals(R.style.Theme_AmoledBlack, AppColorScheme.AMOLED_BLACK.style)
        assertEquals(AppColorScheme.AMOLED_BLACK, AppColorScheme.valueOf("AMOLED_BLACK"))
        assertTrue(AppColorScheme.entries.containsAll(listOf(
            AppColorScheme.IMPERIAL, AppColorScheme.OCEAN, AppColorScheme.FOREST,
            AppColorScheme.ROSE, AppColorScheme.DYNAMIC
        )))
    }
}
