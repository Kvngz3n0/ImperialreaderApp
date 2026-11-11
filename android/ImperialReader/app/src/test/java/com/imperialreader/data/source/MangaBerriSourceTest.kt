package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaBerriSourceTest {
    @Test
    fun parsesCatalogCardsAndNextPage() {
        val html = """<article class="manga-card"><a href="/title/example"><img src="/media/source/cover/1"><h3>Example</h3></a></article><a href="/browse?page=2">Next →</a>"""
        val page = MangaBerriSource.parseListing(html, "https://mangaberri.com/browse")
        assertEquals("Example", page.mangas.single().title)
        assertEquals("https://mangaberri.com/title/example", page.mangas.single().url)
        assertEquals("https://mangaberri.com/media/source/cover/1", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun parsesChapterListWithoutStartReadingLink() {
        val html = """
            <a href="/read/example/0">Start reading</a><div id="chapters-panel">
            <a data-chapter-group="chapters" href="/read/example/2"><strong>Chapter 2</strong><span>0 views</span></a>
            <a data-chapter-group="chapters" href="/read/example/1"><strong>Chapter 1</strong></a></div>
        """
        val chapters = MangaBerriSource.parseChapters(html, "https://mangaberri.com/title/example")
        assertEquals(listOf("Chapter 2", "Chapter 1"), chapters.map { it.name })
        assertEquals(listOf(2f, 1f), chapters.map { it.chapterNumber })
    }
}
