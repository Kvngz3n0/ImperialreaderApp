package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnikotoSourceTest {
    @Test
    fun parsesCatalogAndPagination() {
        val html = """
            <div class="item"><div class="inner"><div class="ani poster">
            <a href="/watch/one-piece-odmau/ep-1"><img src="/cover.jpg"></a></div>
            <div class="info"><div class="b1"><a class="name d-title"
            href="/watch/one-piece-odmau/ep-1">One Piece</a></div></div></div></div>
            <ul class="pagination"><li><a rel="next" href="?page=2">Next</a></li></ul>
        """
        val page = AnikotoSource.parseCatalog(Jsoup.parse(html, "https://anikototv.to/most-viewed"))
        assertEquals("https://anikototv.to/watch/one-piece-odmau", page.mangas.single().url)
        assertEquals("https://anikototv.to/cover.jpg", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun parsesEpisodesNewestFirstAndRejectsInvalidSlugs() {
        val html = """
            <a data-id="1" data-num="1" data-slug="1"><span class="d-title">Episode 1</span></a>
            <a data-id="2" data-num="2" data-slug="2"><span class="d-title">A title</span></a>
            <a data-id="3" data-num="3" data-slug="../bad">Bad</a>
        """
        val episodes = AnikotoSource.parseEpisodes(html, "https://anikototv.to/watch/one-piece-odmau")
        assertEquals(listOf("Episode 2 - A title", "Episode 1"), episodes.map { it.name })
        assertEquals("https://anikototv.to/watch/one-piece-odmau/ep-2", episodes.first().url)
    }

    @Test
    fun parsesDistinctSubAndDubServers() {
        val html = """
            <div class="servers">
              <div class="type" data-type="sub"><ul>
                <li data-link-id="sub-link">HD-1</li>
                <li data-link-id="sub-link">HD-1</li>
              </ul></div>
              <div class="type" data-type="dub"><ul><li data-link-id="dub-link">HD-1</li></ul></div>
              <div class="type" data-type="other"><ul><li data-link-id="other">Other</li></ul></div>
            </div>
        """
        val servers = AnikotoSource.parseServers(html)
        assertEquals(listOf("SUB HD-1", "DUB HD-1"), servers.map { it.label })
        assertEquals(listOf(false, true), servers.map { it.dub })
        assertEquals(listOf("sub-link", "dub-link"), servers.map { it.linkId })
    }
}
