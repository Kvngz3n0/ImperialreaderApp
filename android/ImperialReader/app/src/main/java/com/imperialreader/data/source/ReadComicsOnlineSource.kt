package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/** Adapter for ReadComicsOnline's MMRCMS catalog and advanced-search routes. */
class ReadComicsOnlineSource : HttpSource() {
    override val id = "wp_readcomicsonline_ru"
    override val name = "ReadComicsOnline"
    override val lang = "en"
    override val baseUrl = "https://readcomicsonline.ru"

    private val generic = GenericSource(name, baseUrl)

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/comic-list?sort=views&page=${page.coerceAtLeast(1)}", page)

    override suspend fun getLatest(page: Int): MangasPage =
        list("$baseUrl/comic-list?sort=latest&page=${page.coerceAtLeast(1)}", page)

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/advanced-search?name=${enc(query.trim())}&page=${page.coerceAtLeast(1)}", page)

    private suspend fun list(url: String, page: Int): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val searchResults = "advanced-search" in url
        val items = if (searchResults) doc.select("div a[href]:has(img)")
            else doc.select("div.comic-list-layout .grid > .group")
        val mangas = items.mapNotNull { item ->
            val link = if (searchResults) item else
                item.selectFirst("a.block.text-sm.font-semibold[href], a[href]") ?: return@mapNotNull null
            val path = sitePath(link.absUrl("href"))?.trimEnd('/') ?: return@mapNotNull null
            if (!path.matches(Regex("""^/comic/[^/]+$""", RegexOption.IGNORE_CASE))) return@mapNotNull null
            val image = item.selectFirst("img") ?: link.selectFirst("img")
            val title = listOf(link.attr("title"), item.selectFirst(".text-sm.font-semibold")?.text().orEmpty(), image?.attr("alt").orEmpty(), link.text())
                .map(String::trim).firstOrNull(String::isNotBlank) ?: return@mapNotNull null
            SManga(
                url = "$baseUrl$path",
                title = title,
                thumbnailUrl = imageUrl(image)
            )
        }.distinctBy { it.url }
        return MangasPage(mangas, hasNextPage(doc, page))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = generic.getMangaDetails(manga)

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val chapters = doc.select(".overflow-hidden.border-ink-600 > a[href]").mapNotNull { link ->
            val url = link.absUrl("href")
            if (sitePath(url) == null) return@mapNotNull null
            val name = listOf(link.selectFirst(".text-brand-400")?.text().orEmpty(), link.attr("title"), link.text(), link.attr("aria-label"))
                .map(String::trim).firstOrNull(String::isNotBlank)
                ?: URI(url).path.substringAfterLast('/').replace('-', ' ').ifBlank { return@mapNotNull null }
            SChapter(url = url, name = name, chapterNumber = parseChapterNumber(name))
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("ReadComicsOnline did not return a readable chapter list")
        return chapters.filter { it.chapterNumber >= 0f }.sortedByDescending { it.chapterNumber } +
            chapters.filter { it.chapterNumber < 0f }
    }

    override suspend fun getPages(chapter: SChapter): List<String> = generic.getPages(chapter)

    override fun imageHeaders(): Map<String, String> = generic.imageHeaders()

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.selectFirst("nav a[rel=next]") != null || doc.select("a[href]").any { link ->
            val href = link.absUrl("href")
            val linkedPage = Regex("""[?&]page=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            linkedPage != null && linkedPage > page ||
                link.text().trim().equals("next", ignoreCase = true) || link.classNames().any { it.equals("next", true) }
        }

    private fun sitePath(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        if (!host.equals("readcomicsonline.ru", ignoreCase = true)) return null
        return uri.path
    }

    private fun imageUrl(image: Element): String? =
        listOf("data-src", "data-lazy-src", "src", "data-original")
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let(::absoluteUrl)
}