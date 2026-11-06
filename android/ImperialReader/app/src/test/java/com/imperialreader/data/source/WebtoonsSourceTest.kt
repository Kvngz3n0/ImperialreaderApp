package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonsSourceTest {
    @Test
    fun extractsActualServerRenderedImageUrls() {
        val pages = WebtoonsSource.parseEmbeddedImageList(
            """var imageList = [{ url: "https://webtoon-phinf.pstatic.net/page-1.png", spec: "PNG" }, { url: "https://webtoon-phinf.pstatic.net/page-2.png", spec: "PNG" }];"""
        )

        assertEquals(
            listOf(
                "https://webtoon-phinf.pstatic.net/page-1.png",
                "https://webtoon-phinf.pstatic.net/page-2.png"
            ),
            pages
        )
    }

    @Test
    fun mapsLiveEpisodeRecordsIntoNewestFirstChapters() {
        val chapters = WebtoonsSource.parseWebtoonChapters(
            listOf(
                episode("Episode 1", 1, "https://m.webtoons.com/viewer/1"),
                episode("Episode 2", 2, "https://m.webtoons.com/viewer/2"),
                episode("Episode 3", 3, "https://m.webtoons.com/viewer/3", hasBgm = true)
            )
        )

        assertEquals(listOf(3f, 2f, 1f), chapters.map { it.chapterNumber })
        assertEquals("Episode 3 (ch. 3) ♫", chapters.first().name)
        assertEquals("https://m.webtoons.com/viewer/3", chapters.first().url)
        assertTrue(chapters.first().dateUpload > 0L)
    }

    @Test
    fun assignsSequentialNumbersToNonstandardEpisodeTitles() {
        val chapters = WebtoonsSource.parseWebtoonChapters(
            listOf(
                episode("A New Beginning", -1, "https://m.webtoons.com/viewer/1"),
                episode("The Journey Continues", -1, "https://m.webtoons.com/viewer/2"),
                episode("A Final Chapter", -1, "https://m.webtoons.com/viewer/3")
            )
        )

        assertEquals(listOf(3f, 2f, 1f), chapters.map { it.chapterNumber })
    }

    private fun episode(
        title: String,
        number: Int,
        url: String,
        hasBgm: Boolean = false
    ) = WebtoonEpisode(
        title = title,
        viewerLink = url,
        publishedAt = number.coerceAtLeast(1) * 1_000L,
        episodeNumber = number,
        hasBgm = hasBgm
    )
}
