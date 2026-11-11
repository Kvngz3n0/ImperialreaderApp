package com.imperialreader.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BakaTsukiNovelSourceTest {
    @Test
    fun catalogUsesOnlyProjectEntries() {
        val html = """
            <div id="mw-pages"><ul>
              <li><a href="/project/index.php?title=Absolute_Duo">Absolute Duo</a></li>
              <li><a href="https://elsewhere.test/novel">External</a></li>
            </ul></div>
        """
        val items = BakaTsukiNovelSource.parseCatalog(html)
        assertEquals(listOf("Absolute Duo"), items.map { it.title })
        assertEquals("https://www.baka-tsuki.org/project/index.php?title=Absolute_Duo", items.single().id)
    }

    @Test
    fun chaptersKeepReadingOrderAndExcludeUnrelatedOrMissingPages() {
        val html = """
            <div id="mw-content-text"><div class="mw-parser-output"><ul>
              <li><a href="/project/index.php?title=Absolute_Duo:Volume_1_Prologue">Prologue</a></li>
              <li><a href="/project/index.php?title=Absolute_Duo:Volume_1_Chapter_1">Chapter 1</a></li>
              <li><a class="new" href="/project/index.php?title=Absolute_Duo:Volume_1_Chapter_2">Chapter 2</a></li>
              <li><a href="/project/index.php?title=Other:Chapter_3">Chapter 3</a></li>
              <li><a href="/project/index.php?title=Absolute_Duo:Volume_1_Illustrations">Illustrations</a></li>
            </ul></div></div>
        """
        val url = "https://www.baka-tsuki.org/project/index.php?title=Absolute_Duo"
        assertEquals(listOf("Prologue", "Chapter 1"), BakaTsukiNovelSource.parseChapters(html, url).map { it.title })
        assertTrue(BakaTsukiNovelSource.parseChapters("""<div id="mw-content-text"><div class="mw-parser-output">Removed</div></div>""", url).isEmpty())
    }
}
