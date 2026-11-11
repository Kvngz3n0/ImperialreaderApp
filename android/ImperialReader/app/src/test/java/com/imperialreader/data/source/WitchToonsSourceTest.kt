package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class WitchToonsSourceTest {
    @Test
    fun extractsLegacyAndDrakeComicImagesInResponseOrder() {
        val escapedAmpersand = "\\" + "u0026"
        val html = """{\"imageUrl\":\"/uploads/comic-pages/legacy/page-001.jpg\",\"imageUrl\":\"/uploads/series/hero-x-demon-queen/c102958/p0001.webp?sig=x${escapedAmpersand}exp=2\",\"imageUrl\":\"/uploads/series/hero-x-demon-queen/c102958/p0002.webp\",\"imageUrl\":\"/uploads/series/hero-x-demon-queen/cover.webp\"}"""

        assertEquals(
            listOf(
                "https://drakecomic.net/uploads/comic-pages/legacy/page-001.jpg",
                "https://drakecomic.net/uploads/series/hero-x-demon-queen/c102958/p0001.webp?sig=x&exp=2",
                "https://drakecomic.net/uploads/series/hero-x-demon-queen/c102958/p0002.webp"
            ),
            parseWitchToonsPageUrls(html, "https://drakecomic.net")
        )
    }

    @Test
    fun canonicalizesSavedWitchToonsChapterUrls() {
        assertEquals(
            "https://drakecomic.net/series/comic/example/chapter/12",
            canonicalWitchToonsChapterUrl(
                "https://witchtoons.net/series/comic/example/chapter/12",
                "https://drakecomic.net"
            )
        )
    }

    @Test
    fun retriesSearchWithoutTrailingPunctuation() {
        assertEquals(
            listOf("Apocalypse Descends, I Reincarnated As A Tyrant.", "Apocalypse Descends, I Reincarnated As A Tyrant"),
            witchToonsSearchQueries("Apocalypse Descends, I Reincarnated As A Tyrant.")
        )
    }

    @Test
    fun omitsPaidChaptersUnavailableToTheCurrentAccount() {
        assertFalse(isWitchToonsChapterAccessible(JSONObject("""{"isLocked":true,"hasAccess":false}""")))
        assertTrue(isWitchToonsChapterAccessible(JSONObject("""{"isLocked":true,"hasAccess":true}""")))
        assertTrue(isWitchToonsChapterAccessible(JSONObject("""{"number":1}""")))
    }
}