package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class Manhwa18Source : HttpSource() {
    override val id = "wp_manhwa18_cc"
    override val name = "Manhwa18"
    override val lang = "en"
    override val baseUrl = "https://manhwa18.cc"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/webtoons${if (page > 1) "/$page" else ""}?orderby=trending", page, "div.manga-item")

    override suspend fun getLatest(page: Int): MangasPage =
        list("$baseUrl/webtoons${if (page > 1) "/$page" else ""}?orderby=latest", page, "div.manga-item")

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/search?q=${enc(query)}&page=$page", page, "div.manga-item")

    private suspend fun list(url: String, page: Int, selector: String): MangasPage {
        val doc = try {
            Jsoup.parse(getString(url), url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) {
                return MangasPage(emptyList(), false)
            }
            throw e
        }
        val items = parseMangaCards(doc, selector)
        if (items.isEmpty() && page <= 1) {
            throw IOException("Manhwa18 returned no readable search results")
        }
        val hasNext = doc.select("a[href]").any { a ->
            val target = runCatching { URI(a.absUrl("href")) }.getOrNull() ?: return@any false
            val targetPage = target.path.trimEnd('/').substringAfterLast('/').toIntOrNull()
                ?: target.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("page=") }
                    ?.substringAfter('=')
                    ?.toIntOrNull()
            targetPage != null && targetPage > page
        }
        return MangasPage(items, hasNext && items.isNotEmpty())
    }

    private fun parseMangaCards(doc: Document, selector: String): List<SManga> =
        doc.select(selector).mapNotNull { card ->
            val a = card.select("a[href]").firstOrNull { link ->
                val path = runCatching { URI(link.absUrl("href")).path }.getOrNull().orEmpty()
                path.matches(Regex("""/webtoon/[^/]+/?""", RegexOption.IGNORE_CASE))
            } ?: return@mapNotNull null
            val title = listOf(a.attr("title"), card.selectFirst("h2, h3, h4, .title")?.text().orEmpty(), a.text())
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                ?: return@mapNotNull null
            SManga(
                url = a.absUrl("href"),
                title = title,
                thumbnailUrl = lazyImage(card.selectFirst("img"))
            )
        }.distinctBy { it.url }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = lazyImage(
            doc.selectFirst(".manga-info img, .manga-detail img, .thumb img, .cover img")
        ) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null } ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst(".description, .summary")?.text()?.trim()?.ifBlank { null }
            ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val seriesPath = runCatching { URI(manga.url).path }.getOrNull()?.trimEnd('/')
            ?: throw IOException("Manhwa18 returned an invalid series address")
        val chapterPath = Regex("""^${Regex.escape(seriesPath)}/chapter-(\d+(?:-\d+)?)$""", RegexOption.IGNORE_CASE)
        val chapters = doc.select("#chapterlist a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (runCatching { URI(href).host }.getOrNull() != runCatching { URI(baseUrl).host }.getOrNull()) {
                return@mapNotNull null
            }
            val path = runCatching { URI(href).path }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
            val match = chapterPath.matchEntire(path) ?: return@mapNotNull null
            val number = match.groupValues[1].replace('-', '.').toFloatOrNull()
                ?: return@mapNotNull null
            val sourceName = a.attr("title").trim().ifBlank { a.text().trim() }
            val name = sourceName.takeIf { parseChapterNumber(it) == number } ?: "Chapter ${match.groupValues[1].replace('-', '.')}"
            SChapter(
                url = href,
                name = name,
                dateUpload = parseSiteDate(a.parent()?.selectFirst(".chapter-time")?.text().orEmpty()),
                chapterNumber = number
            )
        }.distinctBy { it.url }

        if (chapters.isEmpty()) throw IOException("Manhwa18 returned no valid chapters for this series")
        return chapters.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select(".read-content img").mapNotNull(::lazyImage).distinct()
        if (pages.isEmpty()) throw IOException("Manhwa18 returned no readable pages for this chapter")
        return pages
    }
}
