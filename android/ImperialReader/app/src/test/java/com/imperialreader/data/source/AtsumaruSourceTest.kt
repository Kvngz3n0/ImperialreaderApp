package com.imperialreader.data.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtsumaruSourceTest {
    @Test
    fun catalogFiltersAdultAndNovelEntries() {
        val json = JSONObject("""{"found":30,"hits":[
            {"document":{"id":"a1","title":"Title","isAdult":false,"hidden":false,"medium":"Comic","poster":"/static/posters/a.jpg"}},
            {"document":{"id":"a2","title":"Adult","isAdult":true,"hidden":false,"medium":"Comic"}},
            {"document":{"id":"a3","title":"Novel","isAdult":false,"hidden":false,"medium":"Novel"}}
        ]}""")
        val page = AtsumaruSource.parseCatalog(json, 1)
        assertEquals("https://atsu.moe/manga/a1", page.mangas.single().url)
        assertEquals("https://atsu.moe/static/posters/a.jpg", page.mangas.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun chaptersAndImagesUseActualApiShapes() {
        val chapters = AtsumaruSource.parseChapters(JSONObject("""{"chapters":[
            {"id":"two","title":"Chapter 2","number":2,"pageCount":2},
            {"id":"one","title":"Chapter 1","number":1,"pageCount":1},
            {"id":"empty","pageCount":0}
        ]}"""), "series")
        assertEquals(listOf("Chapter 2", "Chapter 1"), chapters.map { it.name })
        val pages = AtsumaruSource.parsePages(JSONObject("""{"readChapter":{"pages":[
            {"number":1,"image":"/static/pages/a/1.webp"},
            {"number":0,"image":"/static/pages/a/0.webp"},
            {"number":2,"image":"https://other.example/file"}
        ]}}"""))
        assertEquals(listOf("https://cdn.atsu.moe/static/pages/a/0.webp", "https://cdn.atsu.moe/static/pages/a/1.webp"), pages)
    }
}
