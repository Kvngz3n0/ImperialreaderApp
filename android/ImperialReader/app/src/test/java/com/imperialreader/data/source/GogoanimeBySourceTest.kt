package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GogoanimeBySourceTest {
    @Test
    fun parsesSeriesCatalogAndNextPage() {
        val html = """
            <article class="bs"><div class="bsx">
            <a href="/series/example/" title="Example"><img src="/cover.jpg"></a></div></article>
            <div class="hpage"><a href="?page=2">Next</a></div>
        """
        val page = GogoanimeBySource.parseCatalog(Jsoup.parse(html, "https://gogoanime.by/series/"))
        assertEquals("https://gogoanime.by/series/example/", page.mangas.single().url)
        assertEquals("https://gogoanime.by/cover.jpg", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun keepsEpisodesNewestFirstAndExcludesExternalLinks() {
        val html = """
            <div class="episodes-container">
            <div class="episode-item"><a href="/example-episode-2/">Episode 2</a></div>
            <div class="episode-item"><a href="/example-episode-1/">Episode 1</a></div>
            <div class="episode-item"><a href="https://other.example/episode/">External</a></div>
            </div>
        """
        val episodes = GogoanimeBySource.parseEpisodes(Jsoup.parse(html, "https://gogoanime.by/series/example/"))
        assertEquals(listOf("Episode 2", "Episode 1"), episodes.map { it.name })
    }
}
