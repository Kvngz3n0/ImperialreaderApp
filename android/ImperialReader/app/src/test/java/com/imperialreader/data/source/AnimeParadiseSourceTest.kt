package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeParadiseSourceTest {
    @Test
    fun parsesSearchAndRecentCatalogs() {
        val search = JSONObject("""{"data":[{"title":"Frieren","link":"sousou-no-frieren","posterImage":{"large":"https://example.com/cover.jpg"}}],"pagination":{"hasNext":true}}""")
        val page = AnimeParadiseSource.parseCatalog(search, false)
        assertEquals("https://www.animeparadise.moe/anime/sousou-no-frieren", page.mangas.single().url)
        assertEquals("https://example.com/cover.jpg", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
        val recent = JSONObject("""{"data":[{"origin":{"title":"Frieren","link":"sousou-no-frieren"}},{"origin":{"title":"Frieren","link":"sousou-no-frieren"}}],"pagination":{"hasNext":false}}""")
        assertEquals(1, AnimeParadiseSource.parseCatalog(recent, true).mangas.size)
    }

    @Test
    fun episodesAreNewestFirstWithRealWatchUrls() {
        val entries = JSONArray("""[{"uid":"ep-one","number":"1","title":"Beginning"},{"uid":"ep-two","number":"2","title":"Next"}]""")
        val chapters = AnimeParadiseSource.parseEpisodes(entries, "series123")
        assertEquals(listOf("Episode 2 - Next", "Episode 1 - Beginning"), chapters.map { it.name })
        assertEquals("https://www.animeparadise.moe/watch/ep-two?origin=series123", chapters.first().url)
    }
}
