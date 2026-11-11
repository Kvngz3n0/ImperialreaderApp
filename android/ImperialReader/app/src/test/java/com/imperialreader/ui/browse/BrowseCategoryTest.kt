package com.imperialreader.ui.browse

import com.imperialreader.data.source.CreatorSearchSource
import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseCategoryTest {
    @Test
    fun movesRequestedSourcesWithoutMovingOtherAdultSources() {
        assertTrue(isMediaSource("animepahetv_to", true))
        assertTrue(isMediaSource("anime_uniquestream_net", true))
        assertTrue(isMediaSource("sfmcompile_club", false))
        assertTrue(isMediaSource("rule34_tube", false))
        assertTrue(isMediaSource("miohentai_com", false))
        assertTrue(isMediaSource("rule34_world", false))
        assertFalse(isMediaSource("mangaberri", false))
    }

    @Test
    fun labelsMediaMigrationDestinationsWithoutRelabelingOtherSources() {
        assertEquals("Anime • Media", sourceCategoryName("anime", true, "Anime"))
        assertEquals("SFM • Media", sourceCategoryName("sfmcompile_club", false, "SFM"))
        assertEquals("Manga", sourceCategoryName("manga", false, "Manga"))
    }

    @Test
    fun ordersReadingSourcesByPrimaryCategoryAndAdultStatus() {
        fun source(id: String, name: String, adult: Boolean = false): Source = object : FakeSource() {
            override val id = id
            override val name = name
            override val nsfw = adult
        }
        val sources = listOf(
            source("adult-a", "Adult A", true),
            source("webtoons_en", "WEBTOON"),
            source("mangadex", "MangaDex"),
            source("wp_asurascans_com", "AsuraScans"),
            source("custom", "Custom"),
            source("manhwazone", "ManhwaZone"),
            source("madara_toonily_com", "Toonily"),
            source("adult-z", "Adult Z", true),
            source("wp_manhwa18_cc", "Manhwa18", true)
        )
        assertEquals(
            listOf("Custom", "MangaDex", "AsuraScans", "ManhwaZone", "Toonily", "WEBTOON", "Adult A", "Adult Z", "Manhwa18"),
            sortReadingSources(sources).map { it.name }
        )
    }

    @Test
    fun searchesCreatorsRatherThanPostsAndLeavesOrdinarySourcesAlone() = runBlocking {
        val ordinary = FakeSource()
        val creator = FakeCreatorSource()
        assertEquals("post", searchSourceEntries(ordinary, "query", 2).mangas.single().title)
        assertEquals("creator", searchSourceEntries(creator, "query", 2).mangas.single().title)
        assertEquals("query:2", creator.lastSearch)
    }

    private open class FakeSource : Source {
        override val id = "fake"
        override val name = "Fake"
        override val lang = "en"
        override val baseUrl = "https://example.org"
        override suspend fun getPopular(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatest(page: Int) = MangasPage(emptyList(), false)
        override suspend fun search(query: String, page: Int) = MangasPage(listOf(SManga("/", "post")), false)
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapters(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPages(chapter: SChapter) = emptyList<String>()
        override fun imageHeaders() = emptyMap<String, String>()
    }

    private class FakeCreatorSource : FakeSource(), CreatorSearchSource {
        var lastSearch = ""
        override suspend fun searchCreators(query: String, page: Int): MangasPage {
            lastSearch = "$query:$page"
            return MangasPage(listOf(SManga("/", "creator")), false)
        }
    }
}
