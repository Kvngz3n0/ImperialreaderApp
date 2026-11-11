package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MgekoSourceTest {
    @Test
    fun parsesCatalogAndPagination() {
        val json = """{"page":1,"num_pages":2,"results_html":"<article class='comic-card'><div class='comic-card__cover'><img src='https://images.example/cover.jpg'></div><h3 class='comic-card__title'><a href='/manga/example/'>Example</a></h3></article>"}"""
        val result = MgekoSource.parseCatalog(json, "https://www.mgeko.cc")
        assertEquals("https://www.mgeko.cc/manga/example/", result.mangas.single().url)
        assertEquals("Example", result.mangas.single().title)
        assertEquals("https://images.example/cover.jpg", result.mangas.single().thumbnailUrl)
        assertTrue(result.hasNextPage)
        assertFalse(MgekoSource.parseCatalog("""{"page":2,"num_pages":2,"results_html":""}""", "https://www.mgeko.cc").hasNextPage)
    }

    @Test
    fun readsChapterLinksInSiteOrderAndDeduplicates() {
        val html = """
            <a href="/reader/en/example-2/"><strong class="chapter-title">2-eng-li</strong></a>
            <a href="/reader/en/example-1/"><strong class="chapter-title">1-eng-li</strong></a>
            <a href="/reader/en/example-1/"><strong class="chapter-title">1-eng-li</strong></a>
        """
        val chapters = MgekoSource.parseChapters(html, "https://www.mgeko.cc/manga/example/all-chapters/")
        assertEquals(listOf(2f, 1f), chapters.map { it.chapterNumber })
        assertEquals("https://www.mgeko.cc/reader/en/example-1/", chapters.last().url)
    }
}
