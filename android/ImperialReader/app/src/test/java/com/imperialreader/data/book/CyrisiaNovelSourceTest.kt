package com.imperialreader.data.book

import org.junit.Assert.assertEquals
import org.junit.Test

class CyrisiaNovelSourceTest {
    @Test
    fun catalogIncludesOnlyNonMatureWebNovelsAndSortsByViews() {
        val json = """
          [
            {"name":"A & B","kind":"webnovel","epubs":["1 - Start.txt"],"count":2,"views":5},
            {"name":"Popular","kind":"webnovel","epubs":["1.txt"],"count":1,"views":50},
            {"name":"Mature","kind":"webnovel","epubs":["1.txt"],"count":1,"mature":true},
            {"name":"Comic","kind":"comic","epubs":["1.cbz"],"count":1}
          ]
        """
        val items = CyrisiaNovelSource.parseCatalog(json)
        assertEquals(listOf("Popular", "A & B"), items.map { it.title })
        assertEquals("https://cyrisia.com/series/A%20%26%20B", items[1].id)
        assertEquals("https://cyrisia.com/api/cover?series=A%20%26%20B&v=1%20-%20Start.txt", items[1].coverUrl)
    }

    @Test
    fun chaptersUseCompleteEmbeddedListInOrder() {
        val html = """
          <script type="application/json" id="ch-data">{"series":"A & B","rows":[
          ["00001 - First.txt","Chapter 1","date"],["00002 - Next.txt","Chapter 2","date"],
          ["illustration.jpg","Image","date"]]}</script>
        """
        val chapters = CyrisiaNovelSource.parseChapters(html, "https://cyrisia.com/series/A%20%26%20B")
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
        assertEquals("https://cyrisia.com/read/A%20%26%20B/00001%20-%20First.txt", chapters[0].url)
    }
}
