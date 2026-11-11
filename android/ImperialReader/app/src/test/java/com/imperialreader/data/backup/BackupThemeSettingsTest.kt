package com.imperialreader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupThemeSettingsTest {
    @Test
    fun routesThemeValuesAwayFromDefaultPreferences() {
        val split = splitThemeSettings(
            mapOf(
                "keep_screen_on" to true,
                "theme_mode" to "DARK",
                "color_scheme" to "OCEAN"
            )
        )

        assertEquals(mapOf("keep_screen_on" to true), split.appSettings)
        assertEquals(mapOf("theme_mode" to "DARK", "color_scheme" to "OCEAN"), split.appearanceSettings)
    }

    @Test
    fun olderBackupsWithoutAppearanceSettingsRemainSupported() {
        val split = splitThemeSettings(mapOf("keep_screen_on" to true))

        assertEquals(mapOf("keep_screen_on" to true), split.appSettings)
        assertEquals(emptyMap<String, String>(), split.appearanceSettings)
    }
}