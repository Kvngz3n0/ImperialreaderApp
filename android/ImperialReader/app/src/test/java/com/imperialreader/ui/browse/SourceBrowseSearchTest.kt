package com.imperialreader.ui.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBrowseSearchTest {
    @Test
    fun routesCreatorAndGeneralSearchSeparately() {
        for (tab in listOf(0, 1)) {
            assertTrue(isCreatorSearch(true, 2, tab))
            assertFalse(isGalleryBrowse(true, true, 2, tab))
        }
        assertFalse(isCreatorSearch(true, 2, 3))
        assertTrue(isGalleryBrowse(true, true, 2, 3))
        assertTrue(isGalleryBrowse(true, true, 3, 3))
    }

    @Test
    fun otherSourcesRetainTheirSearchRouting() {
        assertFalse(isCreatorSearch(false, 2, 0))
        assertTrue(isGalleryBrowse(true, false, 2, 0))
        assertFalse(isGalleryBrowse(false, false, 2, 0))
    }
}
