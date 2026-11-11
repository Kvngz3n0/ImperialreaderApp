package com.imperialreader.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private fun q(text: String) = LibraryQuery.parse(text)
    private fun LibraryQuery.hit(
        title: String = "Solo Leveling",
        author: String? = "Chugong",
        genre: String? = "Action, Fantasy",
        source: String = "MangaDex"
    ) = matches(title, author, null, genre, source, "mangadex")

    @Test
    fun plainWordsSearchTitleAuthorGenreAndSource() {
        assertTrue(q("leveling").hit())
        assertTrue(q("chugong").hit())
        assertTrue(q("fantasy").hit())
        assertTrue(q("mangadex").hit())
        assertFalse(q("romance").hit())
    }

    @Test
    fun prefixesLimitTheField() {
        assertTrue(q("source:mangadex").hit())
        assertTrue(q("ext:MangaDex").hit())
        assertFalse(q("source:fantasy").hit())
        assertTrue(q("author:chugong").hit())
        assertFalse(q("author:action").hit())
        assertTrue(q("genre:action").hit())
        assertFalse(q("genre:mangadex").hit())
    }

    @Test
    fun quotedValuesAndExclusionsWork() {
        assertTrue(q("title:\"solo leveling\"").hit())
        assertFalse(q("-genre:action").hit())
        assertTrue(q("-genre:romance fantasy").hit())
    }

    @Test
    fun unknownPrefixIsTreatedAsPlainText() {
        assertTrue(q("re:zero").hit(title = "Re:Zero"))
    }
}
