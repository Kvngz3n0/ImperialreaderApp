package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class ManhwaZoneParsingTest {
    private val base = "https://manhwazone.com"

    @Test
    fun listingCardsGiveOneEntryPerSeriesWithTitleAndCover() {
        val html = """
            <a href="https://manhwazone.com/series/when-cinderellas-magic-fades-away-ym283"><img src="https://media.manhwazone.com/ic/245x350/cover/when-cinderellas-magic-fades-away.webp" alt="When Cinderella’s Magic Fades Away cover"> Manhwa</a>
            <a href="https://manhwazone.com/series/when-cinderellas-magic-fades-away-ym283">When Cinderella’s Magic Fades Away</a>
            <a href="https://manhwazone.com/preview/np6d5x">Read →</a>
            <a href="https://manhwazone.com/series">Series</a>
        """.trimIndent()
        val items = ManhwaZoneSource.parseListing(html, base)
        assertEquals(1, items.size)
        assertEquals("https://manhwazone.com/series/when-cinderellas-magic-fades-away-ym283", items[0].url)
        assertEquals("When Cinderella’s Magic Fades Away", items[0].title)
        assertEquals("https://media.manhwazone.com/ic/245x350/cover/when-cinderellas-magic-fades-away.webp", items[0].thumbnailUrl)
    }

    @Test
    fun trendingCardTitleIsTakenFromTheRepeatedName() {
        val html = """<a href="https://manhwazone.com/series/viral-hit-zw1e0">Viral Hit 1 Weekly Viral Hit Chapter 218.5</a>"""
        assertEquals("Viral Hit", ManhwaZoneSource.parseListing(html, base).single().title)
    }

    @Test
    fun chapterLinksBecomeNumberedChaptersWithoutDates() {
        val html = """
            <a href="/preview/np6d5x">Chapter 113 NEW</a>
            <a href="/preview/xy3p0j">Chapter 112 Oct 29, 2026</a>
            <a href="/preview/xy3p0j">Chapter 112 Oct 29, 2026</a>
        """.trimIndent()
        val chapters = ManhwaZoneSource.parseChapters(html, "https://manhwazone.com/series/x-ym283")
        assertEquals(listOf("Chapter 113", "Chapter 112"), chapters.map { it.name })
        assertEquals(listOf(113f, 112f), chapters.map { it.chapterNumber })
        assertEquals("https://manhwazone.com/preview/np6d5x", chapters[0].url)
    }

    @Test
    fun slugFallbackDropsTheIdSuffix() {
        assertEquals("Viral Hit", ManhwaZoneSource.titleFromSlug("viral-hit-zw1e0"))
    }
}
