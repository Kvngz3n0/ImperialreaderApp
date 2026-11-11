package com.imperialreader.data.repo

import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.db.ReadingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReadingMonitorTest {
    @Test
    fun trackingIsOffByDefaultAndNeverRecordsIncognitoSessions() {
        assertTrue(!ReadingMonitor.shouldRecord(enabled = false, incognito = false))
        assertTrue(!ReadingMonitor.shouldRecord(enabled = true, incognito = true))
        assertTrue(ReadingMonitor.shouldRecord(enabled = true, incognito = false))
    }

    @Test
    fun rangeStartUsesRequestedWindow() {
        val now = 1_800_000_000_000L

        assertEquals(now - 7L * 24 * 60 * 60 * 1000, ReadingMonitor.rangeStart(ReadingRange.DAYS_7, now))
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, ReadingMonitor.rangeStart(ReadingRange.DAYS_30, now))
        assertEquals(now - 365L * 24 * 60 * 60 * 1000, ReadingMonitor.rangeStart(ReadingRange.YEAR, now))
        assertEquals(Long.MIN_VALUE, ReadingMonitor.rangeStart(ReadingRange.ALL, now))
    }

    @Test
    fun calculatesTimeRangeAndBackfilledCompletionCounts() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayMidpoint = todayStart + (now - todayStart) / 2
        val yesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val mangas = listOf(
            MangaEntity(id = 1, source = "manga_source", url = "manga", title = "Manga", genre = "Action, Fantasy"),
            MangaEntity(id = 2, source = "anime_source", url = "anime", title = "Anime", genre = "Drama")
        )
        val sessions = listOf(
            ReadingSessionEntity(1, "MANGA", 1, 11, todayMidpoint, 30 * 60_000L, 8),
            ReadingSessionEntity(2, "NOVEL", 1, 12, yesterday + 60_000, 20 * 60_000L, 12),
            ReadingSessionEntity(3, "ANIME", 2, 21, todayMidpoint, 60 * 60_000L, 0),
            ReadingSessionEntity(4, "MANGA", 1, 13, now - 10L * 24 * 60 * 60 * 1000, 9_000_000L, 100)
        )
        val completed = listOf(
            ChapterEntity(id = 11, mangaId = 1, url = "manga/1", name = "Chapter 1", read = true),
            ChapterEntity(id = 21, mangaId = 2, url = "anime/1", name = "Episode 1", read = true)
        )

        val stats = ReadingMonitor.calculate(
            sessions = sessions,
            mangas = mangas,
            completedChapters = completed,
            animeSourceIds = setOf("anime_source"),
            sourceNames = mapOf("manga_source" to "Manga Source", "anime_source" to "Anime Source"),
            range = ReadingRange.DAYS_7,
            now = now
        )

        assertEquals(60 * 60_000L, stats.watchedMs)
        assertEquals(50 * 60_000L, stats.readMs)
        assertEquals(20L, stats.pagesRead)
        assertEquals(2, stats.titlesCompleted)
        assertEquals(1, stats.chaptersRead)
        assertEquals(1, stats.episodesWatched)
        assertEquals(2, stats.streakDays)
        assertEquals(60 * 60_000L, stats.longestSessionMs)
        assertEquals(2, stats.dailyMs.size)
        assertEquals("Manga Source", stats.bySource.keys.first { it == "Manga Source" })
        assertTrue(stats.topGenres.any { it.first == "Action" && it.second == 50 * 60_000L })
    }

    @Test
    fun csvEscapesFieldsAndQuotes() {
        val manga = MangaEntity(id = 1, source = "source", url = "entry", title = "A \"title\", again")
        val session = ReadingSessionEntity(1, "MANGA", 1, 5, 123, 456, 2)

        val csv = ReadingMonitor.csv(listOf(session), mapOf(1L to manga))

        assertTrue(csv.contains("\"A \"\"title\"\", again\""))
        assertTrue(csv.contains("\"source\""))
    }
}
