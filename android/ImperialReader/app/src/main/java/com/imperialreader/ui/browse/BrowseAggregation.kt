package com.imperialreader.ui.browse

import com.imperialreader.data.book.BookItem
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.book.BookSource
import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class FeedKind { LATEST, EXTENSIONS, MEDIA, ADULT }

/** Presents a book/novel catalog to the aggregator without converting entries into manga; originals stay in [items]. */
internal class BookFeedSource(private val book: BookSource) : Source {
    val items = java.util.concurrent.ConcurrentHashMap<String, BookItem>()
    override val id get() = "book:${book.id}"
    override val name get() = book.name
    val inGlobalSearch get() = book.inGlobalSearch
    override val lang = "en"
    override val baseUrl get() = book.webUrl.orEmpty()
    override suspend fun getPopular(page: Int) = getLatest(page)
    override suspend fun getLatest(page: Int) = toPage(book.popular(page))
    override suspend fun search(query: String, page: Int) = toPage(book.search(query, page))

    private fun toPage(results: List<BookItem>) = MangasPage(results.map {
        items[it.id] = it
        SManga(url = it.id, title = it.title, thumbnailUrl = it.coverUrl, author = it.author)
    }, results.isNotEmpty())
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapters(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPages(chapter: SChapter) = emptyList<String>()
    override fun imageHeaders() = emptyMap<String, String>()
}

internal fun eligibleSources(sources: List<Source>, kind: FeedKind, adultAllowed: Boolean): List<Source> =
    sources.filter {
        when (kind) {
            FeedKind.LATEST -> !it.nsfw && !isMediaSource(it.id, it.isAnime)
            FeedKind.EXTENSIONS -> it is BookFeedSource
            FeedKind.MEDIA -> !it.nsfw && isMediaSource(it.id, it.isAnime)
            // MangaDex mixes ratings, so it also appears here instead of being excluded as ambiguous.
            FeedKind.ADULT -> adultAllowed && (it.nsfw || it.id == "mangadex")
        }
    }.distinctBy { it.id }

internal data class FeedEntry(val source: Source, val manga: SManga, val page: Int, val rank: Int, val sourceOrder: Int)
internal data class FeedFailure(val source: Source, val message: String, val challengeUrl: String? = null)
internal data class FeedSnapshot(
    val items: List<FeedEntry>,
    val failures: List<FeedFailure>,
    val hasMore: Boolean
)

internal class BrowseAggregation(
    private val sources: List<Source>,
    private val timeoutMs: Long,
    concurrency: Int = 4,
    private val query: String? = null,
    private val gate: (suspend (suspend () -> MangasPage) -> MangasPage)? = null
) {
    private data class Cursor(
        var page: Int = 1,
        var hasMore: Boolean = true,
        var failure: String? = null,
        var challengeUrl: String? = null
    )
    private val cursors = sources.associate { it.id to Cursor() }
    private val items = linkedMapOf<Pair<String, String>, FeedEntry>()
    private val limiter = Semaphore(concurrency)

    fun snapshot(): FeedSnapshot = FeedSnapshot(
        items.values.sortedWith(compareBy<FeedEntry> { it.page }.thenBy { it.rank }.thenBy { it.sourceOrder }),
        sources.mapNotNull { source -> cursors.getValue(source.id).failure?.let { FeedFailure(source, it, cursors.getValue(source.id).challengeUrl) } },
        cursors.values.any { it.hasMore && it.failure == null }
    )

    suspend fun load(retryOnly: Boolean = false, retryFailed: Boolean = false, onUpdate: (FeedSnapshot) -> Unit = {}) = coroutineScope {
        sources.mapIndexedNotNull { index, source ->
            val cursor = cursors.getValue(source.id)
            if (!cursor.hasMore || (retryOnly && cursor.failure == null) || (!retryOnly && !retryFailed && cursor.failure != null)) {
                return@mapIndexedNotNull null
            }
            async {
                val pageNumber = cursor.page
                try {
                    val request: suspend () -> MangasPage = {
                        withTimeout(timeoutMs) {
                            withContext(Dispatchers.IO) {
                                if (query == null) source.getLatest(pageNumber) else source.search(query, pageNumber)
                            }
                        }
                    }
                    val page = gate?.invoke(request) ?: limiter.withPermit { request() }
                    page.mangas.forEachIndexed { rank, manga ->
                        if (manga.url.isNotBlank()) {
                            items.putIfAbsent(source.id to manga.url, FeedEntry(source, manga, pageNumber, rank, index))
                        }
                    }
                    cursor.page++
                    cursor.hasMore = page.hasNextPage
                    cursor.failure = null
                    cursor.challengeUrl = null
                } catch (e: TimeoutCancellationException) {
                    cursor.failure = "Timed out"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cursor.failure = e.message?.takeIf { it.isNotBlank() } ?: "Request failed"
                    cursor.challengeUrl = (e as? CloudflareException)?.url
                }
                onUpdate(snapshot())
            }
        }.awaitAll()
    }
}
