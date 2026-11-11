package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HentaiMamaSourceTest {
    private val base = "https://hentaimama.io/"

    @Test
    fun sourceIsAdultAnime() {
        val source = HentaiMamaSource()
        assertEquals("hentaimama_io", source.id)
        assertTrue(source.nsfw)
        assertTrue(source.isAnime)
    }

    @Test
    fun parsesListingAndPagination() {
        val doc = Jsoup.parse("""
            <article class="item tvshows"><div class="poster"><img src="https://hentaimama.io/a.webp"></div>
              <div class="data"><h3><a href="https://hentaimama.io/tvshows/one/">One</a></h3></div></article>
            <article class="item tvshows"><div class="data"><h3><a href="https://outside.test/tvshows/two/">Two</a></h3></div></article>
            <div class="pagination-wraper"><a class="dt-pg-next" href="https://hentaimama.io/tvshows/page/2/">n</a></div>
        """, base)
        val page = HentaiMamaSource.parseListing(doc)
        assertEquals(listOf("https://hentaimama.io/tvshows/one/"), page.mangas.map { it.url })
        assertTrue(page.hasNextPage)
    }

    @Test(expected = IOException::class)
    fun rejectsUnknownListingMarkup() {
        HentaiMamaSource.parseListing(Jsoup.parse("<html></html>", base))
    }

    @Test
    fun searchFiltersBlockedAndUntaggedCards() {
        fun card(title: String, tag: String?) = """
            <article class="series-card"><a class="sc-poster" href="#"><img src="https://hentaimama.io/c.webp"></a>
              <h3 class="sc-title"><a href="https://hentaimama.io/tvshows/${title.lowercase().replace(' ', '-')}/">$title</a></h3>
              <div class="sc-genres">${tag?.let { """<a href="/genre/x/" rel="tag">$it</a>""" }.orEmpty()}</div></article>
        """
        val doc = Jsoup.parse(
            card("Safe", "Anal") + card("Hidden", "Shoutacon") + card("Loli Show", "Comedy") + card("Untagged", null), base
        )
        assertEquals(listOf("Safe"), HentaiMamaSource.parseSearch(doc).mangas.map { it.title })
    }

    @Test
    fun emptySearchIsNotAnError() {
        assertTrue(HentaiMamaSource.parseSearch(Jsoup.parse("""<body class="search search-no-results"></body>""", base)).mangas.isEmpty())
    }

    @Test
    fun requiresSeriesTags() {
        try {
            HentaiMamaSource.validateSeries(Jsoup.parse("<html></html>", base))
            throw AssertionError("Expected missing tags to be rejected")
        } catch (_: IOException) {
        }
        assertEquals(listOf("Anal"), HentaiMamaSource.validateSeries(
            Jsoup.parse("""<div class="dsc-genres"><a rel="tag">Anal</a></div>""", base)
        ))
    }

    @Test
    fun parsesEpisodesNewestFirst() {
        val doc = Jsoup.parse("""
            <div class="dt-se-list">
              <a class="dt-se-item" href="https://hentaimama.io/episodes/x-episode-1/"><b class="dt-se-num">EP 1</b><b class="dt-se-title">Episode 1</b></a>
              <a class="dt-se-item" href="https://hentaimama.io/episodes/x-episode-2/"><b class="dt-se-num">EP 2</b><b class="dt-se-title">Episode 2</b></a>
              <a class="dt-se-item" href="https://outside.test/episodes/x-episode-3/"><b class="dt-se-num">EP 3</b></a>
            </div>""", base)
        val episodes = HentaiMamaSource.parseEpisodes(doc)
        assertEquals(listOf("Episode 2", "Episode 1"), episodes.map { it.name })
        assertEquals(2f, episodes.first().chapterNumber)
    }

    @Test
    fun parsesMirrorsAndPostId() {
        val doc = Jsoup.parse("""
            <div id="dt-dl-modal" data-post="3804"></div>
            <ul class="dt-mi-tabs"><li><a href="#option-5">mi-1</a></li><li><a href="#option-3">mi-2</a></li></ul>
        """, base)
        assertEquals("3804", HentaiMamaSource.parsePostId(doc))
        assertEquals(listOf("mi-1" to 5, "mi-2" to 3), HentaiMamaSource.parseMirrors(doc))
    }

    @Test
    fun acceptsOnlySameSiteHlsEmbeds() {
        fun json(src: String) = """["", "", "<iframe src=\"$src\"></iframe>"]"""
        val good = "https://hentaimama.io/?dt_embed=hls&#038;p=abc&#038;ep=1"
        assertEquals("https://hentaimama.io/?dt_embed=hls&p=abc&ep=1", HentaiMamaSource.parseEmbedUrl(json(good), 3))
        assertNull(HentaiMamaSource.parseEmbedUrl(json("https://outside.test/?dt_embed=hls"), 3))
        assertNull(HentaiMamaSource.parseEmbedUrl(json("https://hentaimama.io/newjav.php?p=abc"), 3))
        assertNull(HentaiMamaSource.parseEmbedUrl(json(good), 1))
    }

    @Test
    fun validatesHlsHost() {
        fun page(url: String) = """sources: [{"file":"${url.replace("/", "\\/")}","type":"hls"}]"""
        val safe = "https://hentaidoge.org/s/1/sig/output/S/x/1/hls/master.m3u8"
        assertEquals(safe, HentaiMamaSource.parseHlsUrl(page(safe)))
        assertNull(HentaiMamaSource.parseHlsUrl(page("https://outside.test/master.m3u8")))
        assertNull(HentaiMamaSource.parseHlsUrl(page("http://hentaidoge.org/master.m3u8")))
        assertNull(HentaiMamaSource.parseHlsUrl("no stream here"))
    }
}
