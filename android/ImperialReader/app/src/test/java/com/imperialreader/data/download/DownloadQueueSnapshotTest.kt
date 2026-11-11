package com.imperialreader.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueSnapshotTest {
    @Test
    fun roundTripsQueuedFailedAndAnimeTasks() {
        val tasks = listOf(
            PersistedDownloadTask(mangaId = 1, chapterId = 11),
            PersistedDownloadTask(mangaId = 2, chapterId = 22, dub = true),
            PersistedDownloadTask(mangaId = 3, chapterId = 33, failure = "HTTP 503")
        )

        assertEquals(tasks, decodeDownloadQueueSnapshot(encodeDownloadQueueSnapshot(tasks)))
    }

    @Test
    fun treatsMissingOrEmptySnapshotAsNoQueuedTasks() {
        assertEquals(emptyList<PersistedDownloadTask>(), decodeDownloadQueueSnapshot(null))
        assertEquals(emptyList<PersistedDownloadTask>(), decodeDownloadQueueSnapshot("{}"))
    }
}