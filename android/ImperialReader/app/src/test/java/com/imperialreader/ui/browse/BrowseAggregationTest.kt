package com.imperialreader.ui.browse

import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class BrowseAggregationTest {
    private class Fake(
        override val id: String,
        override val nsfw: Boolean = false,
        override val isAnime: Boolean = false,
        val latest: suspend (Int) -> MangasPage
    ) : Source {
        override val name = id
        override val lang = "en"
        override val baseUrl = "https://example.test"
        override suspend fun getPopular(page: Int) = getLatest(page)
        override suspend fun getLatest(page: Int) = latest(page)
        val searched = mutableListOf<String>()
        override suspend fun search(query: String, page: Int): MangasPage { searched += query; return getLatest(page) }
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapters(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPages(chapter: SChapter) = emptyList<String>()
        override fun imageHeaders() = emptyMap<String, String>()
    }

    @Test
    fun separatesAdultAnimeAndRegularSources() {
        val regular = Fake("manga") { MangasPage(emptyList(), false) }
        val anime = Fake("anime", isAnime = true) { MangasPage(emptyList(), false) }
        val adult = Fake("adult", nsfw = true) { MangasPage(emptyList(), false) }
        val adultAnime = Fake("adult-anime", nsfw = true, isAnime = true) { MangasPage(emptyList(), false) }
        val mangaDex = Fake("mangadex") { MangasPage(emptyList(), false) }
        val all = listOf(regular, anime, adult, adultAnime, mangaDex)
        assertEquals(listOf(regular, mangaDex), eligibleSources(all, FeedKind.LATEST, true))
        assertEquals(listOf(regular, mangaDex), eligibleSources(all, FeedKind.LATEST, false))
        assertEquals(listOf(anime), eligibleSources(all, FeedKind.MEDIA, true))
        assertTrue(eligibleSources(all, FeedKind.ADULT, false).isEmpty())
        assertEquals(listOf(adult, adultAnime, mangaDex), eligibleSources(all, FeedKind.ADULT, true))
    }

    @Test
    fun partialFailureRetryAndPaginationPreserveResultsAndIdentity() = runBlocking {
        var fail = true
        val pages = mutableListOf<Int>()
        val first = Fake("first") { page ->
            pages.add(page)
            MangasPage(listOf(SManga("/same", "First"), SManga("/same", "Duplicate")), page == 1)
        }
        val second = Fake("second") {
            if (fail) throw IOException("offline")
            MangasPage(listOf(SManga("/same", "Second")), false)
        }
        val feed = BrowseAggregation(listOf(first, second), 2_000)
        feed.load()
        assertEquals(1, feed.snapshot().items.size)
        assertEquals(listOf("second"), feed.snapshot().failures.map { it.source.id })
        assertTrue(feed.snapshot().hasMore)
        fail = false
        feed.load(retryOnly = true)
        assertEquals(2, feed.snapshot().items.size)
        assertTrue(feed.snapshot().failures.isEmpty())
        feed.load()
        assertEquals(listOf(1, 2), pages)
        assertEquals(2, feed.snapshot().items.size)
        assertFalse(feed.snapshot().hasMore)
    }

    @Test
    fun requestsAreBoundedAndCancellationPropagates() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val sources = (0 until 8).map { n ->
            Fake("$n") {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    delay(30)
                    MangasPage(emptyList(), false)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        BrowseAggregation(sources, 2_000, 2).load()
        assertTrue(peak.get() <= 2)
        val slow = Fake("slow") { delay(10_000); MangasPage(emptyList(), false) }
        val job = launch { BrowseAggregation(listOf(slow), 20_000).load() }
        delay(50)
        job.cancel(CancellationException("test"))
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun timeoutIsReportedAndRefreshStartsAtFirstPage() = runBlocking {
        val pages = mutableListOf<Int>()
        val source = Fake("source") { page ->
            pages.add(page)
            MangasPage(listOf(SManga("/$page", "Title $page")), true)
        }
        val slow = Fake("slow") { delay(1_000); MangasPage(emptyList(), false) }
        val feed = BrowseAggregation(listOf(source, slow), 50)
        feed.load()
        assertEquals("Timed out", feed.snapshot().failures.single().message)
        feed.load()
        BrowseAggregation(listOf(source), 50).load()
        assertEquals(listOf(1, 2, 1), pages)
    }

    @Test
    fun scrollRetriesFailedSourceWhileLoadingNextPages() = runBlocking {
        var attempts = 0
        val flaky = Fake("flaky") { page ->
            if (++attempts == 1) error("boom")
            MangasPage(listOf(SManga("/f$page", "F$page")), false)
        }
        val steady = Fake("steady") { page -> MangasPage(listOf(SManga("/s$page", "S$page")), true) }
        val feed = BrowseAggregation(listOf(flaky, steady), 2_000)
        feed.load()
        assertEquals(listOf("flaky"), feed.snapshot().failures.map { it.source.id })
        feed.load(retryFailed = true)
        assertTrue(feed.snapshot().failures.isEmpty())
        assertEquals(setOf("/s1", "/s2", "/f1"), feed.snapshot().items.map { it.manga.url }.toSet())
    }

    @Test
    fun searchQueriesSourcesAndUsesGate() = runBlocking {
        var gated = 0
        val source = Fake("s") { MangasPage(listOf(SManga("/x", "x")), false) }
        val feed = BrowseAggregation(listOf(source), 2_000, query = "naruto", gate = { gated++; it() })
        feed.load()
        assertEquals(listOf("naruto"), source.searched)
        assertEquals(1, gated)
        assertEquals(1, feed.snapshot().items.size)
    }
}
