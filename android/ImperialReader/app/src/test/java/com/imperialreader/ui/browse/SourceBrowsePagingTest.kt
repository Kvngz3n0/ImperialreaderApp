package com.imperialreader.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBrowsePagingTest {
    @Test
    fun continuesWhenEmptyShortOrDuplicateOnlyPagesLeaveTheEndVisible() {
        assertEquals(CreatorPagingAction.LOAD, creatorPagingAction(true, 0, -1, 1))
        assertEquals(CreatorPagingAction.LOAD, creatorPagingAction(true, 1, 0, 2))
        assertEquals(CreatorPagingAction.LOAD, creatorPagingAction(true, 9, 8, 3))
    }

    @Test
    fun offersManualContinuationAfterFivePages() {
        assertEquals(CreatorPagingAction.OFFER_MORE, creatorPagingAction(true, 0, -1, 5))
        assertEquals(CreatorPagingAction.OFFER_MORE, creatorPagingAction(true, 1, 0, 5))
        assertEquals(CreatorPagingAction.LOAD, creatorPagingAction(true, 1, 0, 0))
    }

    @Test
    fun stopsAtEndOrWhenThereAreEnoughOffscreenItems() {
        assertEquals(CreatorPagingAction.STOP, creatorPagingAction(false, 0, -1, 1))
        assertEquals(CreatorPagingAction.STOP, creatorPagingAction(true, 20, 8, 1))
    }
}
