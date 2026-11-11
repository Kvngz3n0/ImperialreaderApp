package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class Rule34TubeSourceTest {
    @Test
    fun parsesOnlyExplicitArtistLinksAndVideoCards() {
        val doc = Jsoup.parse("""
            <nav><a href="/models/navigation/">Navigation</a></nav>
            <div class="block flex"><a class="btn gold" href="/models/artist/">Artist</a></div>
            <div class="thumb"><a href="/video/example/" title="Example"><img src="/cover.jpg"></a></div>
        """, "https://rule34.tube/")
        assertEquals("Artist", Rule34TubeSource.parseArtists(doc, null).single().title)
        val video = Rule34TubeSource.parseVideos(doc).single()
        assertEquals("https://rule34.tube/video/example/", video.url)
        assertEquals("https://rule34.tube/cover.jpg", video.thumbnailUrl)
    }

    @Test
    fun rejectsMissingOrInvalidPlayerData() {
        assertThrows(IOException::class.java) { Rule34TubeSource.resolveVideo("<html></html>") }
        assertThrows(IOException::class.java) {
            Rule34TubeSource.resolveVideo("var flashvars = {video_url: 'function/0/https://rule34.tube/get_file/3/hash/file.mp4/', license_code: 'bad'};")
        }
    }

    @Test
    fun acceptsDirectHttpsPlayerUrl() {
        val html = "var flashvars = {video_url: 'https://rule34.tube/file.mp4/'};"
        assertEquals("https://rule34.tube/file.mp4/", Rule34TubeSource.resolveVideo(html))
    }

    @Test
    fun resolvesEncodedPlayerUrl() {
        val html = """var flashvars = {video_url: 'function/0/https://rule34.tube/get_file/3/0123456789abcdef0123456789abcdef/path/video.mp4/', license_code: '${'$'}432939214283421'};"""
        assertEquals(
            "https://rule34.tube/get_file/3/d0bc1a60f7a2359bc538eed6f1424897/path/video.mp4/",
            Rule34TubeSource.resolveVideo(html)
        )
    }
}
