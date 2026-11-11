package com.imperialreader.ui.more

import com.imperialreader.data.db.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryManagementTest {
    private val categories = listOf(
        CategoryEntity(id = 7, name = "Reading", sortOrder = 0),
        CategoryEntity(id = 12, name = "Finished", sortOrder = 1)
    )

    @Test
    fun categoryNamesAreTrimmedUniqueAndDefaultIsReserved() {
        assertNotNull(categoryNameError("   ", categories))
        assertNotNull(categoryNameError("  reading ", categories))
        assertNotNull(categoryNameError("default", categories))
        assertNull(categoryNameError("Reading", categories, excludedId = 7))
        assertNotNull(categoryNameError("Finished", categories, excludedId = 7))
        assertNull(categoryNameError("Planned", categories))
    }

    @Test
    fun reorderingUsesStableIdsAndRejectsStaleOrDuplicatedLists() {
        assertEquals(listOf(12L, 7L), reorderedCategoryIds(categories, listOf(12, 7)))
        assertNull(reorderedCategoryIds(categories, listOf(7)))
        assertNull(reorderedCategoryIds(categories, listOf(7, 7)))
        assertNull(reorderedCategoryIds(categories, listOf(7, 99)))
    }
}
