package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** https://mangakatana.com — URL patterns confirmed against the live site; HTML selectors are best-effort. */
class MangaKatanaSource : HttpSource() {
    override val id = "mangakatana"
    override val name = "MangaKatana"
    override val lang = "en"
    override val baseUrl = "https://mangakatana.com"

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/manga/page/$page?filter=1&include_mode=and&chapters=1&sort=numc", page)

    override suspend fun getLatest(page: Int): MangasPage = list("$baseUrl/latest/page/$page", page)

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/page/$page?search=${enc(query)}&search_by=book_name", page)

    private suspend fun list(url: String, page: Int): MangasPage {
        val html = try {
            getString(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        val doc = Jsoup.parse(html, baseUrl)

        // A search with exactly one hit redirects straight to the manga page.
        val heading = doc.selectFirst("h1.heading")
        if (heading != null) {
            val canonical = doc.selectFirst("meta[property=og:url]")?.attr("content").orEmpty()
            if (canonical.contains("/manga/")) {
                return MangasPage(listOf(SManga(url = canonical, title = heading.text().trim(), thumbnailUrl = lazyImage(doc.selectFirst("div.cover img")))), false)
            }
        }

        val items = doc.select("#book_list div.item").mapNotNull { el ->
            val a = el.selectFirst("h3.title a, .title a") ?: return@mapNotNull null
            SManga(url = a.absUrl("href"), title = a.text().trim(), thumbnailUrl = lazyImage(el.selectFirst("img")))
        }
        val hasNext = doc.selectFirst("a.next, li a[rel=next]") != null || items.size >= 20
        return MangasPage(items.distinctBy { it.url }, hasNext && items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1.heading")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.author = doc.select(".author").joinToString(", ") { it.text() }.ifBlank { null }
        manga.genre = doc.select(".genres a").joinToString(", ") { it.text() }.ifBlank { null }
        manga.status = statusFromText(doc.selectFirst(".status")?.text().orEmpty())
        manga.description = doc.selectFirst(".summary p")?.text()?.trim()?.ifBlank { null } ?: manga.description
        manga.thumbnailUrl = lazyImage(doc.selectFirst("div.cover img")) ?: manga.thumbnailUrl
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        return doc.select("div.chapters table tr, div.chapters tbody tr").mapNotNull { tr ->
            val a = tr.selectFirst("div.chapter a, a[href*=/c]") ?: return@mapNotNull null
            val name = a.text().trim()
            if (name.isBlank()) return@mapNotNull null
            SChapter(
                url = a.absUrl("href"),
                name = name,
                dateUpload = parseSiteDate(tr.selectFirst("div.update_time, .update_time")?.text().orEmpty()),
                chapterNumber = parseChapterNumber(name)
            )
        }.distinctBy { it.url }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        // The reader embeds its page list as a JS array of quoted image URLs.
        val arrays = Regex("""\[\s*'https?://[^\]]+\]""").findAll(html).map { it.value }
        for (arr in arrays) {
            val urls = Regex("""'(https?://[^']+)'""").findAll(arr).map { it.groupValues[1] }.toList()
            if (urls.size >= 2) return urls
        }
        val doc: Document = Jsoup.parse(html, chapter.url)
        return doc.select("#imgs img").mapNotNull { lazyImage(it) }
    }
}
