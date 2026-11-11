package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreTagsAndRule34Test {
    private val detailsPage = """
        <html><head><meta name="keywords" content="manga websites, read manga online, free manga"></head><body>
        <header><ul class="main-menu"><li><a href="/manga-genre/romance">Romance</a></li><li><a href="/manga-genre/yaoi">Yaoi</a></li></ul></header>
        <h1>Girl And Science</h1>
        <div class="tags">Tags: <a href="/manga-tag/toonily">Toonily</a></div>
        <h5>Genre(s)</h5>
        <div><a href="/manga-genre/action">Action</a>, <a href="/manga-genre/sci-fi">Sci fi</a>, <a href="/manga-genre/suggestive">SUGGESTIVE</a>, <a href="/manga-genre/action">action</a></div>
        <aside class="related"><a href="/manga-genre/horror">Horror</a></aside>
        </body></html>
    """.trimIndent()

    @Test
    fun genresComeFromTheEntryBlockNotTheMenuOrRelatedBlocks() {
        val genres = extractGenreTags(Jsoup.parse(detailsPage))
        assertEquals(listOf("Action", "Sci fi", "Suggestive"), genres)
        assertFalse(genres.contains("Romance"))
        assertFalse(genres.contains("Toonily"))
    }

    @Test
    fun normalizeDropsJunkAndDuplicates() {
        assertEquals(
            listOf("Slice of Life", "Action"),
            normalizeGenreList(listOf("slice of life", "Slice Of Life (12)", "Genres", "  #Action ", "42", "More"))
        )
    }

    @Test
    fun rule34BlocksUnderageTagsButNotUnrelatedOnes() {
        assertTrue(Rule34WorldSource.isBlockedTags(listOf("1girl", "loli")))
        assertTrue(Rule34WorldSource.isBlockedTags(listOf("shota")))
        assertFalse(Rule34WorldSource.isBlockedTags(listOf("child bearing hips", "blue hair")))
    }

    @Test
    fun rule34ParsesPostMediaAndTags() {
        val html = """
            <html><head><meta name="keywords" content="black souls 2, mabel, 9:16, animated">
            <meta property="og:image" content="https://rule34storage.b-cdn.net/posts/1433/1433364/1433364.picpreview.jpg">
            <meta property="article:published_time" content="2026-11-08T14:16:06Z"></head>
            <body><a href="https://rule34storage.b-cdn.net/posts/1433/1433364/1433364.mov480.mp4">v</a>
            <h4><a href="https://rule34.world/black_souls_2">black souls 2</a></h4></body></html>
        """.trimIndent()
        val post = Rule34WorldSource.parsePost(html, "https://rule34.world/post/1433364")
        assertTrue(post.isVideo)
        assertEquals("https://rule34storage.b-cdn.net/posts/1433/1433364/1433364.mov480.mp4", post.media)
        assertEquals(listOf("black souls 2", "mabel", "animated"), post.tags)
        assertEquals("2026-11-08", post.published)
    }

    @Test
    fun rule34ListingFindsPostIdsAndBuildsThumbnails() {
        val items = Rule34WorldSource.parseListing(
            """<a href="https://rule34.world/post/1433378">x</a><a href="/post/1433377">y</a><a href="/post/1433378">dup</a>""",
            "https://rule34.world"
        )
        assertEquals(2, items.size)
        assertEquals("https://rule34storage.b-cdn.net/posts/1433/1433378/1433378.picpreview.jpg", items[0].thumbnailUrl)
    }

    @Test
    fun rule34RecognizesExplicitCreatorLinksAndTypedTagsOnly() {
        val html = """
            <html><head><meta name="keywords" content="artist: mabel, black souls 2">
            <meta property="og:image" content="https://rule34storage.b-cdn.net/posts/1433/1433364/1433364.picpreview.jpg">
            </head><body>
            <a href="/post/1433364">post</a>
            <a class="user-tag" href="/fan-artist">creator name</a>
            </body></html>
        """.trimIndent()

        val post = Rule34WorldSource.parsePost(html, "https://rule34.world/post/1433364")

        assertEquals(
            listOf(
                Rule34WorldSource.Creator("mabel", "https://rule34.world/mabel"),
                Rule34WorldSource.Creator("creator name", "https://rule34.world/fan-artist")
            ),
            post.creators
        )
        assertTrue(Rule34WorldSource().supportsGeneralBrowse)
    }

    @Test
    fun rule34CollectsMultipleExplicitCreatorTypes() {
        val html = """
            <html><body>
            <a rel="creator" href="/artist-one">Artist One</a>
            <a data-type="poster" href="/poster-two">Poster Two</a>
            </body></html>
        """.trimIndent()

        val creators = Rule34WorldSource.parsePost(html, "https://rule34.world/post/1433364").creators

        assertEquals(
            listOf(
                Rule34WorldSource.Creator("Artist One", "https://rule34.world/artist-one"),
                Rule34WorldSource.Creator("Poster Two", "https://rule34.world/poster-two")
            ),
            creators
        )
    }
}
