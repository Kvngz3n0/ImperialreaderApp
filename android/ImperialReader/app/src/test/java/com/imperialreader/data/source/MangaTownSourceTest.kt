package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTownSourceTest {
    @Test
    fun parsesCardsAndNextPage() {
        val html = """
            <ul class="post-list"><li><div class="post">
              <a class="manga-cover" href="/manga/example"><img src="http://cdn.example/cover.jpg?token=123"></a>
              <div class="post-info"><p class="title">Example</p></div>
            </div></li></ul><a href="/hot/2.html">NEXT</a>
        """
        val result = MangaTownSource.parseListing(html, "https://m.mangatown.com/hot")
        assertEquals("Example", result.mangas.single().title)
        assertEquals("https://m.mangatown.com/manga/example", result.mangas.single().url)
        assertEquals("https://cdn.example/cover.jpg?token=123", result.mangas.single().thumbnailUrl)
        assertTrue(result.hasNextPage)
    }

    @Test
    fun parsesChaptersInSourceOrder() {
        val html = """<ul class="detail-ch-list"><li><a href="/manga/example/c12.5/">C.12.5 Apr 21</a></li><li><a href="/manga/example/c1/">C.1</a></li></ul>"""
        val chapters = MangaTownSource.parseChapters(html, "https://m.mangatown.com/manga/example")
        assertEquals(listOf(12.5f, 1f), chapters.map { it.chapterNumber })
        assertEquals("https://m.mangatown.com/manga/example/c12.5/", chapters.first().url)
    }
}
