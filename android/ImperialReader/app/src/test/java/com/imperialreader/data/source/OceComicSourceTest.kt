package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OceComicSourceTest {
    @Test
    fun parsesCardsAndPagination() {
        val html = """
            <main class="Comics"><div class="items"><div class="item">
              <figure><img src="/cover.jpg"></figure>
              <h2><a href="/comic/123/example" title="Complete title">Truncated...</a></h2>
            </div></div></main>
            <select name="page"><option value="1">1</option><option value="2">2</option></select>
        """
        val first = OceComicSource.parseListing(html, "https://ocecomic.com/comic", 1)
        assertEquals("Complete title", first.mangas.single().title)
        assertEquals("https://ocecomic.com/comic/123/example", first.mangas.single().url)
        assertEquals("https://ocecomic.com/cover.jpg", first.mangas.single().thumbnailUrl)
        assertTrue(first.hasNextPage)
        assertFalse(OceComicSource.parseListing(html, "https://ocecomic.com/comic", 2).hasNextPage)
    }

    @Test
    fun parsesIssuesNewestFirstAndExcludesAds() {
        val html = """
            <div class="issues"><a class="issue-link" href="/comic/123/1#1">Issue #1</a>
            <a class="dirlink" href="https://ads.example/">click here</a>
            <a class="issue-link" href="/comic/123/2#1">Issue #2</a></div>
        """
        val issues = OceComicSource.parseChapters(html, "https://ocecomic.com/comic/123/example")
        assertEquals(listOf("Issue #2", "Issue #1"), issues.map { it.name })
        assertEquals(listOf(2f, 1f), issues.map { it.chapterNumber })
        assertEquals("https://ocecomic.com/comic/123/2", issues.first().url)
    }

    @Test
    fun extractsOnlyReaderImagesInOrder() {
        val html = """
            <main id="Comic"><div class="page issue">
            <figure><img src="https://images.example/1.jpg"></figure>
            <figure><img data-src="https://images.example/2.jpg" src="placeholder"></figure>
            </div><img src="/cover.jpg"></main>
        """
        assertEquals(
            listOf("https://images.example/1.jpg", "https://images.example/2.jpg"),
            OceComicSource.parsePages(html, "https://ocecomic.com/comic/123/1")
        )
    }
}
