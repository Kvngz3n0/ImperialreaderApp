package com.imperialreader.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.HistoryEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.normalizeTitle
import com.imperialreader.util.app
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class MangaRepository(private val ctx: Context) {

    private val db get() = ctx.app.db

    /** Returns the stored row for a source entry, creating a (non-library) row if needed. */
    suspend fun getOrCreate(sourceId: String, s: SManga): MangaEntity {
        val dao = db.mangaDao()
        // Same entry reached through a slightly different URL (http/https, www, trailing slash) = same row.
        val wanted = canonicalUrl(s.url)
        val existing = dao.find(sourceId, s.url)
            ?: dao.getBySource(sourceId).firstOrNull { canonicalUrl(it.url) == wanted }
        if (existing != null) {
            // newer data overwrites the old row instead of creating a second copy
            val updated = existing.copy(
                url = s.url,
                title = s.title.ifBlank { existing.title },
                coverUrl = s.thumbnailUrl ?: existing.coverUrl
            )
            if (updated != existing) dao.update(updated)
            return updated
        }
        dao.insert(
            MangaEntity(
                source = sourceId, url = s.url, title = s.title, author = s.author, artist = s.artist,
                description = s.description, genre = s.genre, status = s.status, coverUrl = s.thumbnailUrl
            )
        )
        return dao.find(sourceId, s.url)!!
    }

    /**
     * One library entry that merges the same title from several sources. Identity = normalised title,
     * so later searches that find extra sources simply add them to the existing entry.
     */
    suspend fun getOrCreateCombined(title: String, members: List<CombinedSource.Member>, coverUrl: String?): MangaEntity {
        val dao = db.mangaDao()
        val key = normalizeTitle(title)
        val existing = dao.getCombined().firstOrNull { normalizeTitle(it.title) == key }
        if (existing != null) {
            val union = CombinedSource.decode(existing.url) + members
            val updated = existing.copy(url = CombinedSource.encode(union), coverUrl = existing.coverUrl ?: coverUrl)
            if (updated != existing) dao.update(updated)
            return updated
        }
        dao.insert(MangaEntity(source = CombinedSource.ID, url = CombinedSource.encode(members), title = title, coverUrl = coverUrl))
        return dao.getCombined().first { normalizeTitle(it.title) == key }
    }

    /** Library entries with the same title coming from a different source/row. */
    suspend fun findDuplicates(manga: MangaEntity): List<MangaEntity> {
        val key = normalizeTitle(manga.title)
        if (key.isBlank()) return emptyList()
        return db.mangaDao().getFavoritesList().filter { it.id != manga.id && normalizeTitle(it.title) == key }
    }

    suspend fun toggleFavorite(manga: MangaEntity): Boolean {
        val newState = !manga.favorite
        db.mangaDao().setFavorite(manga.id, newState, if (newState) System.currentTimeMillis() else 0L)
        return newState
    }

    /** Re-fetches details + chapter list from the source. Returns the number of NEW chapters. */
    suspend fun refresh(manga: MangaEntity): Int {
        if (manga.source == LOCAL_SOURCE_ID) return 0
        val source = ctx.app.sources.get(manga.source)
            ?: throw IOException("Source '${manga.source}' is not available")
        val input = SManga(
            url = manga.url, title = manga.title, thumbnailUrl = manga.coverUrl, author = manga.author,
            artist = manga.artist, description = manga.description, genre = manga.genre, status = manga.status
        )
        val details = source.getMangaDetails(input)
        db.mangaDao().update(
            manga.copy(
                title = details.title.ifBlank { manga.title },
                author = details.author ?: manga.author,
                artist = details.artist ?: manga.artist,
                description = details.description ?: manga.description,
                genre = details.genre ?: manga.genre,
                status = details.status,
                coverUrl = details.thumbnailUrl ?: manga.coverUrl,
                initialized = true,
                lastUpdate = System.currentTimeMillis()
            )
        )
        val chapters = source.getChapters(details)
        return syncChapters(manga.id, chapters, trackUpdates = true)
    }

    /**
     * Merges a fresh chapter list into the database while keeping read/bookmark/download state.
     * [list] must be ordered newest first.
     */
    suspend fun syncChapters(mangaId: Long, list: List<SChapter>, trackUpdates: Boolean): Int {
        val dao = db.chapterDao()
        val existing = dao.getAll(mangaId)
        val byUrl = existing.associateBy { it.url }
        val firstSync = existing.isEmpty()
        val now = System.currentTimeMillis()
        val toInsert = mutableListOf<ChapterEntity>()
        list.distinctBy { it.url }.forEachIndexed { index, c ->
            val number = if (c.chapterNumber >= 0f) c.chapterNumber else parseChapterNumber(c.name)
            val old = byUrl[c.url]
            if (old == null) {
                toInsert.add(
                    ChapterEntity(
                        mangaId = mangaId, url = c.url, name = c.name, scanlator = c.scanlator,
                        dateUpload = c.dateUpload,
                        dateFetch = if (firstSync || !trackUpdates) 0L else now,
                        chapterNumber = number, sourceOrder = index
                    )
                )
            } else {
                dao.updateMeta(old.id, index, c.name, c.dateUpload, number, c.scanlator)
            }
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        return if (firstSync) 0 else toInsert.size
    }

    /** Refreshes every library entry (4 at a time). Returns the total number of new chapters. */
    suspend fun updateLibrary(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int = coroutineScope {
        val favorites = db.mangaDao().getFavorites().filter { it.source != LOCAL_SOURCE_ID }
        val sem = Semaphore(4)
        val done = AtomicInteger(0)
        favorites.map { m ->
            async {
                sem.withPermit {
                    val n = runCatching { refresh(m) }.getOrDefault(0)
                    onProgress(done.incrementAndGet(), favorites.size)
                    n
                }
            }
        }.awaitAll().sum()
    }

    suspend fun recordHistory(chapterId: Long) {
        if (ctx.app.prefs.incognito) return
        db.withTransaction {
            val mangaId = db.chapterDao().getById(chapterId)?.mangaId ?: return@withTransaction
            db.historyDao().deleteForManga(mangaId)
            db.historyDao().upsert(HistoryEntity(chapterId = chapterId, lastRead = System.currentTimeMillis()))
        }
    }

    private fun canonicalUrl(url: String): String =
        url.lowercase().substringAfter("://").removePrefix("www.").trimEnd('/')
}
