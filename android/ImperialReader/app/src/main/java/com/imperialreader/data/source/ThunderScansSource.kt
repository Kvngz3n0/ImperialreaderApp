package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/** Dedicated scraper for Thunderscans EN's comics catalog and MangaReader chapter pages. */
class ThunderScansSource : HttpSource() {

    override val id = "thunderscans"
    override val name = "Thunderscans"
    override val lang = "en"
    override val baseUrl = "https://en-thunderscans.com"

    override suspend fun getPopular(page: Int): MangasPage =
        listing("$baseUrl/comics/?order=popular&page=$page")

    override suspend fun getLatest(page: Int): MangasPage =
        listing("$baseUrl/comics/?order=update&page=$page")

    override suspend fun search(query: String, page: Int): MangasPage {
        val q = query.trim()
        if (q.isBlank()) return MangasPage(emptyList(), false)
        val terms = Regex("""[\p{L}\p{N}]+""").findAll(q.lowercase())
            .map { normalizeTitle(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (terms.isEmpty()) return MangasPage(emptyList(), false)

        val url = if (page == 1) {
            "$baseUrl/?s=${enc(q)}"
        } else {
            "$baseUrl/page/$page/?s=${enc(q)}"
        }
        val result = listing(url)
        val matches = result.mangas.filter { manga ->
            val title = normalizeTitle(manga.title)
            terms.all(title::contains)
        }
        return MangasPage(matches, result.hasNextPage && matches.isNotEmpty())
    }

    private suspend fun listing(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val mangas = doc.select(".listupd .bsx").mapNotNull(::parseCard).distinctBy { it.url }
        val next = doc.selectFirst(
            ".pagination a.r, .pagination a.next, .pagination a[rel=next], .pagination a.page-numbers.next"
        )?.absUrl("href")
        return MangasPage(mangas, mangas.isNotEmpty() && !next.isNullOrBlank() && next != url)
    }

    private fun parseCard(card: Element): SManga? {
        val link = card.selectFirst("a[href*='/comics/']") ?: return null
        val url = link.absUrl("href")
        if (!isSameSite(url) || !URI(url).path.startsWith("/comics/")) return null

        val image = lazyImage(card.selectFirst("img"))
        val title = card.selectFirst(".tt")?.text()?.trim()
            ?.ifBlank { null }
            ?: link.attr("title").trim().ifBlank { null }
            ?: image?.let { card.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null } }
            ?: return null
        return SManga(url = url, title = title, thumbnailUrl = image)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = lazyImage(doc.selectFirst(".thumb img")) ?: manga.thumbnailUrl
        manga.description = doc.selectFirst(".entry-content.entry-content-single[itemprop=description]")
            ?.text()?.trim()?.ifBlank { null } ?: manga.description
        manga.genre = doc.select(".mgen a").joinToString(", ") { it.text().trim() }.ifBlank { null }
        manga.status = statusFromText(metadata(doc, "Status").orEmpty())
        manga.author = metadata(doc, "Author")
        manga.artist = metadata(doc, "Artist")
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val chapters = doc.select("#chapterlist li").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href")
            val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            if (!isSameSite(url) || !path.contains("chapter", ignoreCase = true)) return@mapNotNull null

            val name = item.selectFirst(".chapternum")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.attr("data-title").trim().ifBlank { null }
                ?: link.text().trim().ifBlank { null }
                ?: return@mapNotNull null
            SChapter(
                url = url,
                name = name,
                dateUpload = parseSiteDate(item.selectFirst(".chapterdate")?.text().orEmpty()),
                chapterNumber = parseChapterNumber(item.attr("data-num").ifBlank { name })
            )
        }.distinctBy { it.url }

        if (chapters.isEmpty()) throw IOException("Thunderscans returned no unlocked chapters")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        val readerConfig = Regex(
            """ts_reader\.run\(\s*(\{.*?\})\s*\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1)?.let(::parseReaderConfig)

        if (readerConfig?.optBoolean("protected", false) == true) {
            throw IOException("This Thunderscans chapter is locked")
        }

        val images = readerConfig?.let(::readerImages).orEmpty()

        if (images.isNotEmpty()) return images

        val pageImages = Jsoup.parse(html, chapter.url)
            .select("#readerarea img, img.ts-main-image")
            .mapNotNull(::lazyImage)
            .filter { !it.endsWith("/readerarea.svg") }
            .distinct()
        if (pageImages.isEmpty()) throw IOException("Thunderscans returned no readable pages for this chapter")
        return pageImages
    }

    private fun metadata(doc: Document, label: String): String? =
        doc.select(".tsinfo .imptdt").firstOrNull {
            it.selectFirst("h1")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst("i")?.text()?.trim()?.ifBlank { null }

    private fun parseReaderConfig(raw: String): JSONObject? =
        runCatching { JSONObject(raw) }.getOrNull()

    private fun readerImages(config: JSONObject): List<String> {
        val sources = config.optJSONArray("sources") ?: return emptyList()
        val defaultSource = config.optString("defaultSource")
        val selected = (0 until sources.length())
            .mapNotNull(sources::optJSONObject)
            .firstOrNull { it.optString("source") == defaultSource }
            ?: sources.optJSONObject(0)
            ?: return emptyList()
        val pages = selected.optJSONArray("images") ?: return emptyList()
        return (0 until pages.length())
            .mapNotNull { pages.optString(it).takeIf(::isHttpUrl) }
            .distinct()
    }

    private fun isSameSite(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val siteHost = URI(baseUrl).host
        return uri.scheme in setOf("http", "https") && uri.host.equals(siteHost, ignoreCase = true)
    }

    private fun isHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}
