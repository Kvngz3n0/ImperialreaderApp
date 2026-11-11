package com.imperialreader.data.book

import org.junit.Assert.assertEquals
import org.junit.Test

class WitchCultNovelSourceTest {
    @Test
    fun groupsArcsAndChaptersIncludingSupportedPartners() {
        val html = """
          <article><div class="entry-content">
          <h1>Arc 1 – First</h1><ul>
          <li><a href="https://witchculttranslation.com/prologue/">Prologue</a></li>
          <li><a href="https://witchculttranslation.com/one/">Chapter 1</a></li></ul>
          <h1>Phase 2</h1><ul><li><a href="https://eminenttranslations.com/two/">Chapter 2</a></li></ul>
          <h1>Arc 2 – Second</h1><ul><li><a href="https://kagurojp.wordpress.com/three/">Chapter 3</a></li>
          <li><a href="https://example.com/ad/">Ad</a></li></ul>
          <h1>Arc 3 – PDF only</h1><ul><li><a href="https://witchculttranslation.com/file.pdf">Chapter 4</a></li>
          <li><a href="https://witchculttranslation.com/appendix/">Appendix</a></li></ul>
          </div></article>
        """
        assertEquals(2, WitchCultNovelSource.parseArcs(html).size)
        assertEquals(listOf("Prologue", "Chapter 1", "Chapter 2"), WitchCultNovelSource.parseChapters(html, 1).map { it.title })
        assertEquals(listOf("Chapter 3"), WitchCultNovelSource.parseChapters(html, 2).map { it.title })
    }
}
