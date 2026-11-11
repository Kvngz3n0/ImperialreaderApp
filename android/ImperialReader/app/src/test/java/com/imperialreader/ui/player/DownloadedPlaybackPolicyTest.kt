package com.imperialreader.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedPlaybackPolicyTest {
    @Test
    fun invalidSavedPositionsRestartFromBeginning() {
        assertEquals(0L, downloadedResumePosition(0, 1000))
        assertEquals(0L, downloadedResumePosition(-1, 1000))
        assertEquals(0L, downloadedResumePosition(1000, 1000))
        assertEquals(0L, downloadedResumePosition(2000, 1000))
        assertEquals(500L, downloadedResumePosition(500, 1000))
        assertEquals(500L, downloadedResumePosition(500, -1))
    }

    @Test
    fun endingBeforeFirstFrameNeverCompletesEpisode() {
        assertEquals(DownloadedEndAction.RESTART, downloadedEndAction(false, true))
        assertEquals(DownloadedEndAction.ERROR, downloadedEndAction(false, false))
        assertEquals(DownloadedEndAction.COMPLETE, downloadedEndAction(true, true))
        assertEquals(DownloadedEndAction.COMPLETE, downloadedEndAction(true, false))
    }
}
