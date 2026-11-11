package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MioHentaiSourceTest {
    @Test
    fun parsesSearchResultsAndPagination() {
        val page = MioHentaiSource.parseCatalog(
            """{"success":true,"pages":2,"posts":[{"url":"https://miohentai.com/video/","title":"Video","image":"https://miohentai.com/cover.jpg"}]}""", 1
        )
        assertEquals("Video", page.mangas.single().title)
        assertTrue(page.hasNextPage)
        assertFalse(MioHentaiSource.parseCatalog("""{"success":true,"pages":2,"posts":[]}""", 2).hasNextPage)
    }

    @Test
    fun parsesStudiosAndArchivePages() {
        val doc = Jsoup.parse("""
            <div class="post-metadata"><a href="/studio/example/">Example Studio</a></div>
            <article class="tag-card tag-story"><a class="tag-card-link" href="/episode/">
              <h3 class="tag-card-title">Episode</h3></a></article>
            <article class="tag-card tag-loli"><a class="tag-card-link" href="/blocked/">
              <h3 class="tag-card-title">Blocked</h3></a></article>
            <div class="pagination-above">
              <a aria-current="page" data-page="1" href="/studio/example/">1</a>
              <a data-page="2" href="/studio/example/page/2/">2</a>
            </div>
        """, "https://miohentai.com/studio/example/")
        assertEquals("Example Studio", MioHentaiSource.parseStudios(doc, null).single().title)
        assertEquals("Episode", MioHentaiSource.parseArchive(doc).single().title)
        assertEquals("https://miohentai.com/studio/example/page/2/", MioHentaiSource.nextArchivePage(doc, "https://miohentai.com/studio/example/"))
    }

    @Test
    fun usesAnimePlaybackAndDoesNotAdvertiseCreatorGallery() {
        val source = MioHentaiSource()
        assertEquals("miohentai_com", source.id)
        assertTrue(source.isAnime)
        assertTrue(source.nsfw)
        assertFalse(source.supportsGeneralBrowse)
    }

    @Test
    fun videoParserRejectsBlockedTagsAndUntrustedHosts() {
        fun document(tag: String, url: String) = Jsoup.parse(
            """<div class="post-metadata"><div class="entry-tags"><a href="/tag/$tag/">$tag</a></div></div><video data-video-src="$url"></video>""",
            "https://miohentai.com/video/"
        )
        val safe = "https://cdn.miohentai.com/video.mp4"
        assertEquals(safe, MioHentaiSource.parseVideoUrl(document("safe", safe)))
        for (doc in listOf(document("loli", safe), document("safe", "https://example.com/video.mp4"))) {
            try {
                MioHentaiSource.parseVideoUrl(doc)
                throw AssertionError("Expected video to be rejected")
            } catch (_: IOException) {
            }
        }
    }
}
