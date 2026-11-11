package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class XAnimePornSourceTest {
    private val base = "https://xanimeporn.com/"

    @Test
    fun sourceIsAdultAnime() {
        val source = XAnimePornSource()
        assertTrue(source.nsfw)
        assertTrue(source.isAnime)
        assertEquals("xanimeporn_com", source.id)
    }

    @Test
    fun parsesCatalogAndPaginationWithoutOtherWidgets() {
        val doc = Jsoup.parse("""
            <ul class="listing-videos listing-tube">
              <li><img data-lazy-src="/cover.jpg"><a href="/example-episode-1/" title="Example episode 1">Example</a></li>
              <li><a href="/bad/" title="underage">Blocked</a></li>
              <li><a href="https://elsewhere.test/entry/" title="External">External</a></li>
            </ul>
            <ul class="listing-tube"><li><a href="/other/" title="Widget">Widget</a></li></ul>
            <div class="pagination"><a href="/page/2/?filtre=date">2</a></div>
        """, base)
        val result = XAnimePornSource.parseCatalog(doc, 1)
        assertEquals(listOf("Example episode 1"), result.mangas.map { it.title })
        assertEquals("${base}cover.jpg", result.mangas.single().thumbnailUrl)
        assertTrue(result.hasNextPage)
        assertFalse(XAnimePornSource.parseCatalog(doc, 2).hasNextPage)
    }

    @Test(expected = IOException::class)
    fun rejectsMissingCatalogMarkup() {
        XAnimePornSource.parseCatalog(Jsoup.parse("<html></html>", base), 1)
    }

    @Test
    fun parsesEpisodeListAndDownloadOptions() {
        val doc = Jsoup.parse("""
            <div class="su-tabs-pane" data-title="Episodes">
              <a href="/episode-1/">Episode 1</a><a href="/episode-2/">Episode 2</a>
              <a href="https://outside.test/episode-3/">Episode 3</a>
            </div>
            <div class="su-tabs-pane" data-title="Downloads">
              <a href="https://videos.xanimeporn.com/download.php?id=1&amp;quality=720p">720p</a>
              <a href="https://videos.xanimeporn.com/download.php?id=1&amp;quality=1080p">1080p</a>
              <a href="https://outside.test/download.php">2160p</a>
            </div>
        """, base)
        assertEquals(listOf("Episode 2", "Episode 1"), XAnimePornSource.parseEpisodes(doc).map { it.name })
        assertEquals(listOf("1080p", "720p"), XAnimePornSource.parseDownloads(doc).map { it.first })
    }

    @Test
    fun rejectsBlockedTagsAndTitles() {
        fun doc(tag: String, title: String) = Jsoup.parse(
            """<h1>$title</h1><div id="cat-tag"><a rel="tag">$tag</a></div>""", base
        )
        assertTrue(XAnimePornSource.blockedPost(doc("underage", "Example")))
        assertTrue(XAnimePornSource.blockedPost(doc("safe", "Loli example")))
        assertFalse(XAnimePornSource.blockedPost(doc("safe", "Example")))
    }

    @Test(expected = IOException::class)
    fun rejectsPostsWithoutTags() {
        XAnimePornSource.blockedPost(Jsoup.parse("<h1>Example</h1>", base))
    }
}
