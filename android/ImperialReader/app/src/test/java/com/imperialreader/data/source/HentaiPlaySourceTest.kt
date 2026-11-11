package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HentaiPlaySourceTest {
    private val base = "https://hentaiplay.net/hentai/episodes/"

    @Test
    fun sourceIsAdultAnime() {
        val source = HentaiPlaySource()
        assertEquals("hentaiplay_net", source.id)
        assertTrue(source.nsfw)
        assertTrue(source.isAnime)
    }

    @Test
    fun parsesCatalogAndFiltersBlockedOrMissingTags() {
        val doc = Jsoup.parse("""
            <div class="loop-content">
              <div class="item item-post"><a class="clip-link" href="/episode-1/" title="Episode 1"></a>
                <img src="/cover.jpg"><a rel="tag" href="/genre/action/">Action</a></div>
              <div class="item item-post"><a class="clip-link" href="/blocked/" title="Blocked"></a>
                <a rel="tag" href="/genre/lolicon/">Lolicon</a></div>
              <div class="item item-post"><a class="clip-link" href="/untagged/" title="Untagged"></a></div>
            </div>
            <div class="wp-pagenavi"><a class="nextpostslink" href="/hentai/episodes/page/2/">Next</a></div>
        """, base)
        val page = HentaiPlaySource.parseCatalog(doc)
        assertEquals(listOf("Episode 1"), page.mangas.map { it.title })
        assertEquals("https://hentaiplay.net/cover.jpg", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun handlesEmptyResults() {
        val result = HentaiPlaySource.parseCatalog(Jsoup.parse("""<div class="post no-results"></div>""", base))
        assertTrue(result.mangas.isEmpty())
        assertFalse(result.hasNextPage)
    }

    @Test(expected = IOException::class)
    fun rejectsMissingCatalogMarkup() {
        HentaiPlaySource.parseCatalog(Jsoup.parse("<html></html>", base))
    }

    @Test
    fun validatesSeriesPagination() {
        val series = "https://hentaiplay.net/episode-list/example/"
        fun page(next: String) = Jsoup.parse(
            """<div class="wp-pagenavi"><a class="nextpostslink" href="$next">Next</a></div>""", series
        )
        val safe = "${series}page/2/"
        assertEquals(safe, HentaiPlaySource.nextSeriesPage(page(safe), series))
        assertEquals(null, HentaiPlaySource.nextSeriesPage(page("https://outside.test/page/2/"), series))
        assertEquals(null, HentaiPlaySource.nextSeriesPage(page("https://hentaiplay.net/episode-list/other/page/2/"), series))
    }

    @Test
    fun validatesVideoUrl() {
        fun page(url: String) = Jsoup.parse("""<video><source src="$url"></video>""", base)
        val safe = "https://hentaiplanet.info/episode.mp4"
        assertEquals(safe, HentaiPlaySource.parseVideoUrl(page(safe)))
        for (url in listOf("https://outside.test/episode.mp4", "https://hentaiplanet.info/watch")) {
            try {
                HentaiPlaySource.parseVideoUrl(page(url))
                throw AssertionError("Expected unsupported video URL to be rejected")
            } catch (_: IOException) {
            }
        }

    }

    @Test
    fun validatesPostTagsAndTitles() {
        fun page(tag: String, title: String) = Jsoup.parse("""
            <h1 class="entry-title">$title</h1>
            <div class="post" id="post-1"><a rel="tag" href="/genre/$tag/">$tag</a></div>
        """, base)
        HentaiPlaySource.validatePost(page("action", "Episode"))
        for (doc in listOf(page("underage", "Episode"), page("action", "Loli Episode"), Jsoup.parse("", base))) {
            try {
                HentaiPlaySource.validatePost(doc)
                throw AssertionError("Expected unsafe or unclassified post to be rejected")
            } catch (_: IOException) {
            }
        }
    }
}
