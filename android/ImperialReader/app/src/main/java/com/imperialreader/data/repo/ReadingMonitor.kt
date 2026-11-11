package com.imperialreader.data.repo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.db.ReadingSessionEntity
import com.imperialreader.util.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ReadingMediaKind { MANGA, NOVEL, ANIME }

enum class ReadingRange(val label: String) {
    DAYS_7("7 days"),
    DAYS_30("30 days"),
    YEAR("Year"),
    ALL("All time")
}

data class ReadingMonitorStats(
    val watchedMs: Long,
    val readMs: Long,
    val pagesRead: Long,
    val titlesCompleted: Int,
    val chaptersRead: Int,
    val episodesWatched: Int,
    val streakDays: Int,
    val averagePerDayMs: Long,
    val longestSessionMs: Long,
    val dailyMs: Map<LocalDate, Long>,
    val topTitles: List<Pair<String, Long>>,
    val topGenres: List<Pair<String, Long>>,
    val byMediaKind: Map<String, Long>,
    val bySource: Map<String, Long>
)

object ReadingMonitor {
    fun shouldRecord(enabled: Boolean, incognito: Boolean): Boolean = enabled && !incognito

    fun rangeStart(range: ReadingRange, now: Long): Long {
        val duration = when (range) {
            ReadingRange.DAYS_7 -> 7L
            ReadingRange.DAYS_30 -> 30L
            ReadingRange.YEAR -> 365L
            ReadingRange.ALL -> return Long.MIN_VALUE
        }
        return now - duration * 24L * 60L * 60L * 1000L
    }

    fun calculate(
        sessions: List<ReadingSessionEntity>,
        mangas: List<MangaEntity>,
        completedChapters: List<ChapterEntity>,
        animeSourceIds: Set<String>,
        sourceNames: Map<String, String>,
        range: ReadingRange,
        now: Long
    ): ReadingMonitorStats {
        val rangeStart = rangeStart(range, now)
        val selected = sessions.filter { it.startedAt in rangeStart..now && it.durationMs > 0L }
        val mangaById = mangas.associateBy { it.id }
        val watchedMs = selected.filter { it.mediaKind == ReadingMediaKind.ANIME.name }.sumOf { it.durationMs }
        val readMs = selected.filter { it.mediaKind != ReadingMediaKind.ANIME.name }.sumOf { it.durationMs }
        val daily = selected.groupBy {
            Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }.mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
        val titlesByTime = selected.groupBy { it.mangaId }.mapNotNull { (id, rows) ->
            mangaById[id]?.let { it.title to rows.sumOf { row -> row.durationMs } }
        }.sortedByDescending { it.second }.take(5)
        val genreTimes = LinkedHashMap<String, Long>()
        selected.forEach { session ->
            val manga = mangaById[session.mangaId] ?: return@forEach
            manga.genre.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct().forEach { genre ->
                genreTimes[genre] = (genreTimes[genre] ?: 0L) + session.durationMs
            }
        }
        val completedByManga = completedChapters.groupBy { it.mangaId }
        val chaptersRead = completedChapters.size
        val episodesWatched = completedByManga.filterKeys { id ->
            mangaById[id]?.source in animeSourceIds
        }.values.sumOf { it.size }
        val activeDays = sessions.filter { it.durationMs > 0L }.map {
            Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }.toSet()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        var streak = 0
        var streakDate = if (today in activeDays) today else today.minusDays(1)
        while (streakDate in activeDays) {
            streak++
            streakDate = streakDate.minusDays(1)
        }
        val totalMs = watchedMs + readMs
        val daysInRange = when (range) {
            ReadingRange.DAYS_7 -> 7L
            ReadingRange.DAYS_30 -> 30L
            ReadingRange.YEAR -> 365L
            ReadingRange.ALL -> {
                val first = sessions.filter { it.durationMs > 0L }.minOfOrNull { it.startedAt }
                    ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                first?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).coerceAtLeast(0L) + 1L } ?: 1L
            }
        }.coerceAtLeast(1L)
        val kindTimes = selected.groupBy { it.mediaKind }.mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
        val sourceTimes = selected.groupBy { mangaById[it.mangaId]?.source ?: "unknown" }
            .mapKeys { (source, _) -> sourceNames[source] ?: source }
            .mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
        return ReadingMonitorStats(
            watchedMs = watchedMs,
            readMs = readMs,
            pagesRead = selected.filter { it.mediaKind != ReadingMediaKind.ANIME.name }.sumOf { it.units.toLong() },
            titlesCompleted = completedByManga.keys.size,
            chaptersRead = chaptersRead - episodesWatched,
            episodesWatched = episodesWatched,
            streakDays = streak,
            averagePerDayMs = totalMs / daysInRange,
            longestSessionMs = selected.maxOfOrNull { it.durationMs } ?: 0L,
            dailyMs = daily,
            topTitles = titlesByTime,
            topGenres = genreTimes.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
            byMediaKind = kindTimes,
            bySource = sourceTimes
        )
    }

    fun csv(sessions: List<ReadingSessionEntity>, mangaById: Map<Long, MangaEntity>): String = buildString {
        appendLine("media_kind,title,source,chapter_id,started_at,duration_ms,units")
        sessions.forEach { session ->
            val manga = mangaById[session.mangaId]
            appendLine(
                listOf(
                    session.mediaKind,
                    manga?.title.orEmpty(),
                    manga?.source.orEmpty(),
                    session.chapterId.toString(),
                    session.startedAt.toString(),
                    session.durationMs.toString(),
                    session.units.toString()
                ).joinToString(",") { csvField(it) }
            )
        }
    }

    private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

class ReadingSessionRecorder(
    context: Context,
    private val mediaKind: ReadingMediaKind,
    private val mangaId: Long,
    private val chapterId: Long,
    startsActive: Boolean = true
) {
    private val appContext = context.applicationContext
    private val dao = appContext.app.db.readingSessionDao()
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val idleLimitMs = 2 * 60 * 1000L
    private val writeIntervalMs = 10_000L
    private var foreground = false
    private var eligible = startsActive
    private var active = false
    private var lastInteraction = 0L
    private var segmentStarted = 0L
    private var segmentStartedAt = 0L
    private var segmentUnits = 0
    private var storedId = 0L
    private var closed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!active || closed) return
            val now = SystemClock.elapsedRealtime()
            val idleFor = now - lastInteraction
            if (idleFor >= idleLimitMs) {
                stopSegment(lastInteraction + idleLimitMs)
                return
            }
            persist(now, finish = false)
            handler.postDelayed(this, minOf(writeIntervalMs, idleLimitMs - idleFor).coerceAtLeast(1_000L))
        }
    }

    fun onResume() {
        if (closed) return
        foreground = true
        lastInteraction = SystemClock.elapsedRealtime()
        if (eligible) startSegment(lastInteraction)
    }

    fun onPause() {
        foreground = false
        if (active) stopSegment(SystemClock.elapsedRealtime())
    }

    fun onUserInteraction() {
        if (closed) return
        lastInteraction = SystemClock.elapsedRealtime()
        if (foreground && eligible && !active) startSegment(lastInteraction)
    }

    fun setPlaybackActive(isPlaying: Boolean) {
        if (closed) return
        if (isPlaying) {
            val wasEligible = eligible
            eligible = true
            if (!wasEligible) lastInteraction = SystemClock.elapsedRealtime()
            if (foreground && !active) startSegment(lastInteraction)
        } else {
            eligible = false
            if (active) stopSegment(SystemClock.elapsedRealtime())
        }
    }

    fun addUnits(count: Int) {
        if (active && count > 0) segmentUnits += count
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(ticker)
        if (active) stopSegment(SystemClock.elapsedRealtime())
    }

    private fun startSegment(now: Long) {
        val prefs = appContext.app.prefs
        val enabled = prefs.sp.getBoolean("reading_monitor_enabled", false)
        if (active || !foreground || !eligible || !ReadingMonitor.shouldRecord(enabled, prefs.incognito)) return
        active = true
        segmentStarted = now
        segmentStartedAt = System.currentTimeMillis()
        segmentUnits = 0
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, writeIntervalMs)
    }

    private fun stopSegment(endElapsed: Long) {
        if (!active) return
        handler.removeCallbacks(ticker)
        active = false
        persist(endElapsed, finish = true)
    }

    private fun persist(endElapsed: Long, finish: Boolean) {
        val duration = (endElapsed - segmentStarted).coerceAtLeast(0L)
        if (duration == 0L) return
        val row = ReadingSessionEntity(
            mediaKind = mediaKind.name,
            mangaId = mangaId,
            chapterId = chapterId,
            startedAt = segmentStartedAt,
            durationMs = duration,
            units = segmentUnits
        )
        writes.launch {
            writeLock.withLock {
                val id = storedId
                if (id == 0L) storedId = dao.insert(row)
                else dao.update(row.copy(id = id))
                if (finish) storedId = 0L
            }
        }
    }
}
