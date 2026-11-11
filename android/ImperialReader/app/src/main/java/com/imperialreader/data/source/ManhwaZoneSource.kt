package com.imperialreader.data.source

import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * manhwazone.com
 *
 * Verified against the live site: the /series list (24 per page, newest first), the /explore "Trending Now" list,
 * series pages (/series/<slug>-<id>, cover, status, genre links, synopsis) and covers on media.manhwazone.com.
 *
 * NOT verified: the site fills the chapter list ("Loading...") and the reader with JavaScript and does not allow
 * automated fetching of reader pages, so chapters and page images are read by opening the page in a hidden WebView,
 * like a normal browser visit, and collecting what the page renders (see [WebViewDom]). If a title shows no chapters
 * or pages the error says which step found nothing.
 */
class ManhwaZoneSource : HttpSource() {
    override val id = "manhwazone"
    override val name = "ManhwaZone"
    override val lang = "en"
    override val baseUrl = "https://manhwazone.com"

    private val firstSeriesByList = ConcurrentHashMap<String, String>()

    /** "Trending Now" (top 20) from the explore page. */
    override suspend fun getPopular(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(parseListing(getString("$baseUrl/explore"), baseUrl), false)
    }

    /** All series, newest update first. */
    override suspend fun getLatest(page: Int): MangasPage = series("$baseUrl/series", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val q = query.trim()
        if (q.isEmpty()) return MangasPage(emptyList(), false)
        throw IOException("ManhwaZone search is unavailable: the site's search endpoint has not been verified")
    }

    private suspend fun series(path: String, page: Int): MangasPage {
        val url = if (page <= 1) path else "$path?page=$page"
        val items = parseListing(getString(url), baseUrl)
        if (items.isEmpty()) return MangasPage(emptyList(), false)
        val first = items.first().url
        if (page <= 1) firstSeriesByList[path] = first
        else if (firstSeriesByList[path] == first) return MangasPage(emptyList(), false) // ?page= ignored
        return MangasPage(items, items.size >= 24)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { manga.title = it }
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: manga.thumbnailUrl
        synopsis(doc)?.let { manga.description = it }
        val genres = extractGenreTags(doc)
        if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
        val text = doc.text()
        Regex("""\b(On Going|Ongoing|Finished|Completed|On Hiatus|Discontinued)\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.let { manga.status = statusFromText(it) }
        return manga
    }

    private fun synopsis(doc: org.jsoup.nodes.Document): String? {
        val heading = doc.select("h2, h3, h4").firstOrNull { it.text().trim().equals("Official Synopsis", true) }
        val block = heading?.nextElementSibling()?.text()
            ?.replace(Regex("""\s*Read more\s*$""", RegexOption.IGNORE_CASE), "")?.trim()
        return block?.takeIf { it.length > 20 }
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val html = WebViewDom.evaluate(
            url = manga.url,
            readyJs = "document.querySelectorAll('a[href*=\"/preview/\"]').length > 0",
            resultJs = "document.documentElement.outerHTML"
        ) ?: throw IOException("ManhwaZone: the chapter list never appeared on ${manga.url} (the site may need a login or has changed)")
        val chapters = parseChapters(html, manga.url)
        if (chapters.isEmpty()) throw IOException("ManhwaZone: no chapter links found on ${manga.url}")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val collect =
            "Array.from(document.images).map(function(i){return i.currentSrc||i.getAttribute('data-src')||i.getAttribute('data-lazy-src')||i.src;})" +
                ".filter(function(u){return u&&u.indexOf('http')===0&&!/\\/cover\\/|\\/ic\\/|logo|avatar|icon|banner|favicon|\\.svg/i.test(u);})"
        val ready = "(function(){var n=($collect).length;var stable=(n===window.__ixc);window.__ixc=n;return n>=2&&stable;})()"
        val raw = WebViewDom.evaluate(
            url = chapter.url,
            referer = "$baseUrl/",
            readyJs = ready,
            resultJs = "JSON.stringify($collect)",
            scroll = true,
            timeoutMs = 45_000
        ) ?: throw IOException("ManhwaZone: no images appeared on ${chapter.url} (the chapter may need a login)")
        val array = JSONArray(raw)
        val pages = (0 until array.length()).map { array.getString(it) }.distinct()
        if (pages.isEmpty()) throw IOException("ManhwaZone: the chapter page rendered no images")
        return pages
    }

    companion object {
        private val seriesPath = Regex("""/series/([a-z0-9-]+)""")
        private val chapterId = Regex("""/preview/([A-Za-z0-9]+)""")
        private val chapterLabel = Regex("""^\s*((?:Chapter|Episode|Ch\.?|Ep\.?|[A-Za-z]{1,6})\s*\d+(?:\.\d+)?)""")
        private val trending = Regex("""^(.+?)\s+\d+\s+Weekly\s+\1\b""")

        /** Series cards from /series, /explore, genre pages: one entry per series URL with title and cover. */
        fun parseListing(html: String, baseUrl: String): List<SManga> {
            val doc = Jsoup.parse(html, baseUrl)
            val found = LinkedHashMap<String, SManga>()
            for (a in doc.select("a[href*=/series/]")) {
                val href = a.absUrl("href").substringBefore('#').substringBefore('?')
                val slug = seriesPath.find(href)?.groupValues?.get(1) ?: continue
                if (href.trimEnd('/').endsWith("/series")) continue
                val url = "${baseUrl.trimEnd('/')}/series/$slug"
                val entry = found.getOrPut(url) { SManga(url = url, title = "", thumbnailUrl = null) }
                val img = a.selectFirst("img")
                if (img != null && entry.thumbnailUrl == null) entry.thumbnailUrl = lazyImage(img)
                if (entry.title.isBlank()) {
                    val text = a.text().trim()
                    val label = when {
                        text.isBlank() || text.startsWith("Read", true) -> ""
                        img != null -> ""
                        else -> trending.find(text)?.groupValues?.get(1) ?: text
                    }
                    val fromAttr = a.attr("title").removeSuffix(" - Read Online").trim()
                    entry.title = label.ifBlank { fromAttr }
                }
            }
            for ((url, entry) in found) {
                if (entry.title.isBlank()) entry.title = titleFromSlug(url.substringAfterLast('/'))
            }
            return found.values.toList()
        }

        /** "viral-hit-zw1e0" -> "Viral Hit" (last resort when a card has no readable title). */
        fun titleFromSlug(slug: String): String =
            slug.substringBeforeLast('-').split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

        /** Chapter links (/preview/<id>) of a rendered series page, newest first as the site lists them. */
        fun parseChapters(html: String, pageUrl: String): List<SChapter> {
            val doc = Jsoup.parse(html, pageUrl)
            val base = Regex("""^https?://[^/]+""").find(pageUrl)?.value ?: pageUrl
            return doc.select("a[href*=/preview/]").mapNotNull { a ->
                val id = chapterId.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val text = a.text().trim()
                val label = chapterLabel.find(text)?.groupValues?.get(1)?.trim()
                val number = Regex("""(\d+(?:\.\d+)?)""").find(label ?: text)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                SChapter(url = "$base/preview/$id", name = label ?: text.ifBlank { "Chapter" }, chapterNumber = number)
            }.distinctBy { it.url }
        }
    }
}
