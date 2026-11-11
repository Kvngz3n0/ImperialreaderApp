package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class HentaiPlaySource : AnimeHttpSource() {
    override val id = "hentaiplay_net"
    override val name = "HentaiPlay"
    override val baseUrl = BASE
    override val nsfw = true

    override suspend fun getPopular(page: Int) = listing("$baseUrl/hentai/episodes/", page)
    override suspend fun getLatest(page: Int) = listing("$baseUrl/hentai/episodes/", page)
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank() || blocked(listOf(query))) MangasPage(emptyList(), false)
        else listing("$baseUrl/", page, "s=${enc(query.trim())}")

    private suspend fun listing(root: String, page: Int, query: String? = null): MangasPage {
        require(page > 0)
        val url = root + if (page == 1) "" else "page/$page/"
        val request = url + (query?.let { "?$it" } ?: "")
        return parseCatalog(Jsoup.parse(getString(request), request))
    }

    private suspend fun checkedPost(url: String): Document {
        if (!isPostUrl(url)) throw IOException("Invalid HentaiPlay episode URL")
        val doc = Jsoup.parse(getString(url), url)
        validatePost(doc)
        return doc
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = checkedPost(manga.url)
        val post = doc.selectFirst("div.post[id^=post-]:not(.item)")!!
        manga.genre = tags(post).joinToString(", ").ifBlank { null }
        manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = checkedPost(manga.url)
        val series = doc.selectFirst(".seriesmetatop a[href]")?.absUrl("href")
        if (series == null || !isSeriesUrl(series)) {
            return listOf(SChapter(manga.url, manga.title, chapterNumber = episodeNumber(manga.title)))
        }
        val episodes = linkedMapOf<String, SChapter>()
        val seen = mutableSetOf<String>()
        var next: String? = series
        while (next != null && seen.add(next)) {
            val page = Jsoup.parse(getString(next), next)
            parseCatalog(page).mangas.forEach { item ->
                episodes.putIfAbsent(item.url, SChapter(item.url, item.title, chapterNumber = episodeNumber(item.title)))
            }
            next = nextSeriesPage(page, series)
        }
        if (episodes.isEmpty()) throw IOException("HentaiPlay returned no available episodes")
        return episodes.values.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        val url = parseVideoUrl(checkedPost(chapter.url))
        return listOf(VideoOption("MP4", false) { ChapterPages(listOf(url), imageHeaders()) })
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages =
        getVideoOptions(episode).first().resolve()

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages {
        if (dub) throw IOException("HentaiPlay does not provide a separate dub")
        return loadEpisode(chapter)
    }

    companion object {
        private const val BASE = "https://hentaiplay.net"
        private val numberPattern = Regex("""(?i)\bepisode\s+(\d+(?:\.\d+)?)""")

        private fun episodeNumber(title: String): Float =
            numberPattern.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

        private fun sameSite(url: String): URI? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            return uri.takeIf { it.scheme == "https" && it.host == "hentaiplay.net" && it.userInfo == null }
        }

        private fun isPostUrl(url: String): Boolean {
            val uri = sameSite(url) ?: return false
            return uri.rawQuery == null && uri.path.matches(Regex("""/[^/]+/"""))
        }

        private fun isSeriesUrl(url: String): Boolean {
            val uri = sameSite(url) ?: return false
            return uri.rawQuery == null && uri.path.matches(Regex("""/episode-list/[^/]+/"""))
        }

        private fun tags(element: Element): List<String> =
            element.select("a[rel=tag][href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        private fun hasTags(element: Element) = tags(element).isNotEmpty()

        private fun blocked(values: List<String>): Boolean =
            Rule34WorldSource.isBlockedTags(values.map { it.replace(Regex("""[^A-Za-z0-9_]+"""), " ") })

        internal fun validatePost(doc: Document) {
            val post = doc.selectFirst("div.post[id^=post-]:not(.item)")
                ?: throw IOException("HentaiPlay episode metadata not found")
            if (!hasTags(post)) throw IOException("HentaiPlay episode tags not found")
            if (blocked(tags(post) + doc.select("h1.entry-title").map { it.text() })) {
                throw IOException("Blocked: this episode is tagged as depicting minors")
            }
        }

        internal fun parseCatalog(doc: Document): MangasPage {
            val cards = doc.select("div.item.item-post")
            if (cards.isEmpty() && doc.selectFirst(".post.no-results") == null &&
                doc.selectFirst(".loop-content") == null) {
                throw IOException("HentaiPlay catalog markup not found")
            }
            val entries = cards.mapNotNull { card ->
                val link = card.selectFirst("a.clip-link[href]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                val title = link.attr("title").trim()
                if (!isPostUrl(url) || title.isBlank() || !hasTags(card) || blocked(tags(card) + title)) return@mapNotNull null
                val cover = card.selectFirst("img[src]")?.absUrl("src")?.takeIf { it.startsWith("https://") }
                SManga(url, title, thumbnailUrl = cover)
            }.distinctBy { it.url }
            val next = doc.selectFirst(".wp-pagenavi a.nextpostslink[href], link[rel=next][href]")?.absUrl("href")
            return MangasPage(entries, next?.let { sameSite(it) != null } == true)
        }

        internal fun nextSeriesPage(doc: Document, seriesUrl: String): String? {
            val next = doc.selectFirst(".wp-pagenavi a.nextpostslink[href], link[rel=next][href]")?.absUrl("href")
                ?: return null
            val uri = sameSite(next) ?: return null
            val root = URI(seriesUrl).path
            return next.takeIf { uri.rawQuery == null && uri.path.matches(Regex(Regex.escape(root) + """page/\d+/""")) }
        }

        internal fun parseVideoUrl(doc: Document): String {
            val source = doc.selectFirst("video source[src]")?.absUrl("src")
                ?: throw IOException("HentaiPlay did not provide a video")
            val uri = runCatching { URI(source) }.getOrNull()
            return source.takeIf {
                uri?.scheme == "https" && uri.host == "hentaiplanet.info" &&
                    uri.userInfo == null && uri.path.endsWith(".mp4", ignoreCase = true)
            } ?: throw IOException("HentaiPlay did not provide a supported MP4")
        }
    }
}
