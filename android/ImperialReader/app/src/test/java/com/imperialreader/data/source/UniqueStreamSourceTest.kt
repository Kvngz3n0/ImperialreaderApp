package com.imperialreader.data.source

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UniqueStreamSourceTest {
    @Test
    fun parsesSeriesAndEpisodes() {
        val catalog = JSONArray("""[{"type":"show","content_id":"abc123","title":"Example Anime","image":"https://example.com/cover.jpg"},{"type":"movie","content_id":"xyz","title":"Movie"}]""")
        val results = UniqueStreamSource.parseCatalog(catalog)
        assertEquals("https://anime.uniquestream.net/series/abc123/Example-Anime", results.mangas.single().url)
        assertFalse(results.hasNextPage)
        val episodes = JSONArray("""[{"content_id":"ep123","episode":"1","title":"Beginning"}]""")
        val chapter = UniqueStreamSource.parseEpisodes(episodes, "Season 1").single()
        assertEquals("Season 1 — Episode 1 — Beginning", chapter.name)
        assertEquals("https://anime.uniquestream.net/watch/ep123", chapter.url)
    }
}
