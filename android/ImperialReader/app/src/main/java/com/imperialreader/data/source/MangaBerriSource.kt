package com.imperialreader.data.source

import org.jsoup.Jsoup
import java.io.IOException

class MangaBerriSource : HttpSource() {
    override val id = "mangaberri"
    override val name = "MangaBerri"
    override val lang = "en"
    override val baseUrl = "https://mangaberri.com"

    override suspend fun getPopular(page: Int): MangasPage = listing("$baseUrl/browse?sort=popular&page=$page")
    override suspend fun getLatest(page: Int): MangasPage = listing("$baseUrl/browse?sort=latest&page=$page")
    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        return listing("$baseUrl/browse?q=${enc(query.trim())}&page=$page")
    }

    private suspend fun listing(url: String): MangasPage = parseListing(getString(url), url)

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst("main h1")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)?.let { manga.thumbnailUrl = it }
        doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf(String::isNotBlank)?.let { manga.description = it }
        val genres = extractGenreTags(doc)
        if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val chapters = parseChapters(getString(manga.url), manga.url)
        if (chapters.isEmpty()) throw IOException("MangaBerri returned no chapters for ${manga.title}")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select(".reader-pages img[data-page-image]").mapNotNull { lazyImage(it) }.distinct()
        if (pages.isEmpty()) throw IOException("MangaBerri returned no pages for ${chapter.name}")
        return pages
    }

    companion object {
        fun parseListing(html: String, url: String): MangasPage {
            val doc = Jsoup.parse(html, url)
            val entries = doc.select("article.manga-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=/title/]") ?: return@mapNotNull null
                val href = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val title = link.selectFirst("h3")?.text()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                SManga(href, title, thumbnailUrl = lazyImage(link.selectFirst("img")))
            }.distinctBy { it.url }
            val next = doc.select("a[href]").any { it.text().trim().startsWith("Next", true) && it.absUrl("href").contains("page=") }
            return MangasPage(entries, next)
        }

        fun parseChapters(html: String, url: String): List<SChapter> =
            Jsoup.parse(html, url).select("#chapters-panel a[data-chapter-group=chapters][href]").mapNotNull { link ->
                val href = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = link.selectFirst("strong")?.text()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val number = Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull() ?: -1f
                SChapter(href, name, chapterNumber = number)
            }.distinctBy { it.url }
    }
}
