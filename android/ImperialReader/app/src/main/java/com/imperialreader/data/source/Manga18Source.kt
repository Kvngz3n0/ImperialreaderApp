package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

class Manga18Source(
    override val name: String,
    override val baseUrl: String
) : HttpSource() {
    override val id = "wp_" + hostId(baseUrl, name)
    override val lang = "en"
    override val nsfw = true

    private val genericReader = GenericSource(name, baseUrl, nsfw = true)

    override suspend fun getPopular(page: Int): MangasPage = mangaList(page, latest = false)

    override suspend fun getLatest(page: Int): MangasPage = mangaList(page, latest = true)

    private suspend fun mangaList(page: Int, latest: Boolean): MangasPage {
        val pageNumber = page.coerceAtLeast(1)
        val route = if (pageNumber == 1) "$baseUrl/manga" else "$baseUrl/manga/$pageNumber"
        val url = if (latest) "$route?orderby=latest" else route
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select(".page-item-detail").mapNotNull { card ->
            val a = card.select("a[href]").firstOrNull { link ->
                val path = runCatching { URI(link.absUrl("href")).path }.getOrNull().orEmpty().trimEnd('/')
                Regex("""^/manga/[^/]+$""").matches(path)
            } ?: return@mapNotNull null
            val title = a.attr("title").trim().ifBlank {
                a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }.ifBlank {
                card.selectFirst("h3, h4, .manga-title")?.text()?.trim().orEmpty()
            }
            if (title.isBlank()) return@mapNotNull null
            SManga(a.absUrl("href"), title, a.selectFirst("img")?.let(::lazyImage))
        }.distinctBy { it.url }
        if (items.isEmpty() && pageNumber == 1) throw IOException("$name returned no readable manga titles")
        val hasNext = doc.selectFirst("#blog-pager a[href], .pagination a[href]") != null && items.isNotEmpty()
        return MangasPage(items, hasNext)
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val url = "$baseUrl/search?q=${enc(query)}" + if (page > 1) "&page=$page" else ""
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select(".page-item-detail").mapNotNull { card ->
            val a = card.select("a[href]").firstOrNull { link ->
                val path = runCatching { URI(link.absUrl("href")).path }.getOrNull().orEmpty().trimEnd('/')
                Regex("""^/manga/[^/]+$""").matches(path)
            } ?: return@mapNotNull null
            val title = a.attr("title").trim().ifBlank {
                a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }.ifBlank {
                card.selectFirst("h3, h4, .manga-title")?.text()?.trim().orEmpty()
            }
            if (title.isBlank()) return@mapNotNull null
            SManga(a.absUrl("href"), title, a.selectFirst("img")?.let(::lazyImage))
        }.distinctBy { it.url }
        if (items.isEmpty() && page <= 1) throw IOException("$name returned no readable search results")
        val hasNext = doc.select("#blog-pager a[href], .pagination a[href]").any { it.absUrl("href") != url } &&
            items.isNotEmpty()
        return MangasPage(items, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst(".summary_image img, .item-thumb img")?.let(::lazyImage)
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst(".description-summary, .summary-content")?.text()?.trim()?.ifBlank { null }
            ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val seriesPath = runCatching { URI(manga.url).path }.getOrNull()?.trimEnd('/')
            ?: throw IOException("$name returned an invalid manga address")
        val chapterPath = Regex("""^${Regex.escape(seriesPath)}/chapter-(\d+(?:-\d+)?)$""", RegexOption.IGNORE_CASE)
        val host = URI(baseUrl).host
        val chapters = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (runCatching { URI(href).host }.getOrNull() != host) return@mapNotNull null
            val path = runCatching { URI(href).path }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
            val match = chapterPath.matchEntire(path) ?: return@mapNotNull null
            val numberText = match.groupValues[1].replace('-', '.')
            val number = numberText.toFloatOrNull() ?: return@mapNotNull null
            val label = a.attr("title").ifBlank { a.text() }.replace(Regex("""\s+"""), " ").trim()
            val chapterName = label.takeIf { parseChapterNumber(it) == number } ?: "Chapter $numberText"
            val date = a.parent()?.selectFirst(".chapter-time, time")?.let {
                it.attr("datetime").ifBlank { it.text() }
            }.orEmpty()
            SChapter(href, chapterName, parseSiteDate(date), number)
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("$name returned no valid chapters for this manga")
        return chapters.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> = genericReader.getPages(chapter)
}
