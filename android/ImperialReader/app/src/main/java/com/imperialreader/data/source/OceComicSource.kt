package com.imperialreader.data.source

import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

class OceComicSource : HttpSource() {
    override val id = "ocecomic_com"
    override val name = "OceComic"
    override val lang = "en"
    override val baseUrl = "https://ocecomic.com"

    override suspend fun getPopular(page: Int): MangasPage =
        listing("$baseUrl/comic/all/popular/page/$page", page)

    override suspend fun getLatest(page: Int): MangasPage =
        listing("$baseUrl/comic/all/latest/page/$page", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        val html = postString("$baseUrl/", mapOf("q" to query.trim()))
        if (page == 1) return parseListing(html, baseUrl, page)
        val doc = Jsoup.parse(html, baseUrl)
        val slug = doc.selectFirst("main#Search .items[data-genre]")?.attr("data-genre")
            ?.takeIf { it.isNotBlank() } ?: throw IOException("OceComic did not return a search page")
        return listing("$baseUrl/search/${enc(slug)}/latest/page/$page", page)
    }

    private suspend fun listing(url: String, page: Int): MangasPage = parseListing(getString(url), url, page)

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst("main h1")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        doc.selectFirst("main figure img")?.let { lazyImage(it) }?.let { manga.thumbnailUrl = it }
        doc.selectFirst("main .detail .about")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.description = it }
        doc.select("main .detail .info ul").firstOrNull {
            it.selectFirst("li span")?.text()?.trim().equals("Writer", true)
        }?.select("li")?.getOrNull(1)?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.author = it }
        val genres = extractGenreTags(doc)
        if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val chapters = parseChapters(getString(manga.url), manga.url)
        if (chapters.isEmpty()) throw IOException("OceComic returned no issues for ${manga.title}")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val pages = parsePages(getString(chapter.url), chapter.url)
        if (pages.isEmpty()) throw IOException("OceComic returned no pages for ${chapter.name}")
        return pages
    }

    companion object {
        private fun isSiteUrl(url: String): Boolean =
            runCatching { URI(url).host.equals("ocecomic.com", true) }.getOrDefault(false)

        fun parseListing(html: String, url: String, page: Int): MangasPage {
            val doc = Jsoup.parse(html, url)
            val items = doc.select("main.Comics .items .item").mapNotNull { card ->
                val link = card.selectFirst("h2 a[href]") ?: return@mapNotNull null
                val href = link.absUrl("href")
                val title = link.attr("title").trim().ifBlank { link.text().trim() }
                if (!isSiteUrl(href) || title.isBlank()) return@mapNotNull null
                SManga(href, title, thumbnailUrl = lazyImage(card.selectFirst("figure img")))
            }.distinctBy { it.url }
            val lastPage = doc.select("select[name=page] option[value]")
                .mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull() ?: page
            return MangasPage(items, page < lastPage)
        }

        fun parseChapters(html: String, url: String): List<SChapter> =
            Jsoup.parse(html, url).select(".issues a.issue-link[href]").mapNotNull { link ->
                val href = link.absUrl("href").substringBefore('#')
                val name = link.text().trim()
                if (!isSiteUrl(href) || name.isBlank()) return@mapNotNull null
                val number = Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull() ?: -1f
                SChapter(href, name, chapterNumber = number)
            }.distinctBy { it.url }.reversed()

        fun parsePages(html: String, url: String): List<String> =
            Jsoup.parse(html, url).select("main#Comic .page.issue figure img")
                .mapNotNull { lazyImage(it) }.distinct()
    }
}
