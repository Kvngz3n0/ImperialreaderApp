package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

class AsuraScansSource : HttpSource() {
    override val id = "wp_asurascans_com"
    override val name = "AsuraScans"
    override val lang = "en"
    override val baseUrl = "https://asurascans.com"

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/browse/comics?page=${page.coerceAtLeast(1)}", page)

    override suspend fun getLatest(page: Int): MangasPage =
        list("$baseUrl/browse/comics?sort=latest&page=${page.coerceAtLeast(1)}", page)

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/browse/comics?search=${enc(query)}&page=${page.coerceAtLeast(1)}", page)

    private suspend fun list(url: String, page: Int): MangasPage {
        val doc = try {
            Jsoup.parse(getString(url), url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        val items = doc.select("div.series-card").mapNotNull { card ->
            val a = card.selectFirst("a[href^='/comics/']:not([href*='/chapter/'])") ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim()
                ?.ifBlank { null }
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            SManga(
                url = a.absUrl("href"),
                title = title,
                thumbnailUrl = a.selectFirst("img")?.let(::imageUrl)
            )
        }.distinctBy { it.url }
        val hasNext = doc.select("a[href*='/browse/comics'][href*='page=']").any { it.absUrl("href") != url } ||
            items.size >= PAGE_SIZE
        return MangasPage(items, hasNext && items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val seriesPath = runCatching { URI(manga.url).path }.getOrNull()?.trimEnd('/')
            ?: throw IOException("AsuraScans returned an invalid comic address")
        val chapterPath = Regex("""^${Regex.escape(seriesPath)}/chapter/(\d+(?:\.\d+)?)$""")
        val chapters = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (runCatching { URI(href).host }.getOrNull() != runCatching { URI(baseUrl).host }.getOrNull()) {
                return@mapNotNull null
            }
            val path = runCatching { URI(href).path }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
            val match = chapterPath.matchEntire(path) ?: return@mapNotNull null
            val number = match.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            val name = label.takeIf { parseChapterNumber(it) == number } ?: "Chapter ${match.groupValues[1]}"
            val date = a.selectFirst("time")?.let { it.attr("datetime").ifBlank { it.text() } }.orEmpty()
            SChapter(
                url = href,
                name = name,
                dateUpload = parseSiteDate(date),
                chapterNumber = number
            )
        }.distinctBy { it.url }

        if (chapters.isEmpty()) throw IOException("AsuraScans returned no valid chapters for this comic")
        return chapters.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val chapterImagePath = "/asura-images/chapters/"
        val images = doc.select("img[src], img[data-src], img[data-lazy-src]")
            .mapNotNull(::imageUrl)
            .filter { chapterImagePath in it }
            .distinct()
            .ifEmpty {
                Regex("""https?://[^"'\s<>]+?\.(?:jpe?g|png|webp|avif)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(doc.select("script").joinToString("\n") { it.data() })
                    .map { it.value }
                    .filter { chapterImagePath in it }
                    .distinct()
                    .toList()
            }
        if (images.isEmpty()) throw IOException("AsuraScans returned no readable pages for this chapter")
        return images
    }

    private fun imageUrl(img: org.jsoup.nodes.Element): String? =
        listOf("data-src", "data-lazy-src", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let(::absoluteUrl)

    private companion object {
        const val PAGE_SIZE = 20
    }
}
