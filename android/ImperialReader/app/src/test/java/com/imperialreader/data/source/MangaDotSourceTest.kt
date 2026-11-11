package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDotSourceTest {
    @Test
    fun parsesIndexedCatalogAndPagination() {
        val data = """[
            {"_1":2},"pages/ViewAllPage",{"_3":4},"data",{"_3":5},
            {"_6":7,"_8":9},"manga_list",[10],"pagination",
            {"_11":12,"_13":14},{"_15":16,"_17":18,"_19":20,"_21":22},
            "current_page",1,"total_pages",2,"id",44063,"title","Example",
            "photo","/cover.jpg","is_adult",false
        ]"""
        val page = MangaDotSource.parseCatalog(data)
        assertEquals("https://mangadot.net/manga/44063", page.mangas.single().url)
        assertEquals("https://mangadot.net/cover.jpg", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun parsesSearchAndExcludesAdultResults() {
        val data = JSONObject("""{"results":[{"id":1,"title":"Safe"},{"id":2,"title":"Adult","is_blurworthy":true}],"pagination":{"current_page":1,"total_pages":2}}""")
        val page = MangaDotSource.parseSearch(data)
        assertEquals(listOf("Safe"), page.mangas.map { it.title })
        assertTrue(page.hasNextPage)
    }

    @Test
    fun parsesIndexedDetails() {
        val data = """[
            {"_1":2},"pages/MangaDetailPage",{"_3":4},"data",{"_5":6},
            "mangaData",{"_7":8},"manga",{"_9":10,"_11":12,"_13":14,"_15":16},
            "id",44063,"title","Example","description","A story","genres",[17,18],
            "Action","Fantasy"
        ]"""
        val details = MangaDotSource.parseDetails(data)
        assertEquals("Example", details.title)
        assertEquals("A story", details.description)
        assertEquals("Action, Fantasy", details.genre)
    }

    @Test
    fun parsesChaptersAndReaderImages() {
        val chapters = JSONArray("""[{"id":10,"chapter_number":1,"language":"en","source":"user"},{"id":11,"chapter_number":2,"chapter_title":"Next","language":"en","source":"mangadex"},{"id":12,"chapter_number":3,"language":"fr"}]""")
        val parsed = MangaDotSource.parseChapters(chapters)
        assertEquals(listOf("Chapter 2 - Next", "Chapter 1"), parsed.map { it.name })
        assertEquals("https://mangadot.net/chapter/10?source=user", parsed.last().url)
        val pages = JSONObject("""{"images":[{"url":"/first.webp"},{"url":"https://cdn.example/second.jpg"}]}""")
        assertEquals(listOf("https://mangadot.net/first.webp", "https://cdn.example/second.jpg"), MangaDotSource.parsePages(pages))
    }
}
