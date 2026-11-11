package com.imperialreader.data.source

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

class MgekoSource : HttpSource() {
    override val id = "mgeko_cc"
    override val name = "Mgeko"
    override val lang = "en"
    override val baseUrl = "https://www.mgeko.cc"

    override suspend fun getPopular(page: Int): MangasPage = catalog("sort=popular_all_time", page)
    override suspend fun getLatest(page: Int): MangasPage = catalog("sort=latest", page)
    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        return catalog("q=${enc(query.trim())}", page)
    }

    private suspend fun catalog(filter: String, page: Int): MangasPage =
        parseCatalog(getString("$baseUrl/browse-comics/data/?$filter&page=$page"), baseUrl)

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst("h1.novel-title")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?.let { manga.thumbnailUrl = it }
        doc.selectFirst("p.description")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?.let { manga.description = it }
        val genres = extractGenreTags(doc)
        if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val fullList = doc.selectFirst("a[href*=/all-chapters/]")?.absUrl("href")
            ?: throw IOException("Mgeko did not provide a full chapter list for ${manga.title}")
        val chapters = parseChapters(getString(fullList), fullList)
        if (chapters.isEmpty()) throw IOException("Mgeko returned no chapter links for ${manga.title}")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("img.mgr-page").mapNotNull { lazyImage(it) }.distinct()
        if (pages.isEmpty()) throw IOException("Mgeko returned no reader images for ${chapter.name}")
        return pages
    }

    companion object {
        fun parseCatalog(json: String, baseUrl: String): MangasPage {
            val data = JSONObject(json)
            if (!data.has("results_html") || !data.has("page") || !data.has("num_pages")) {
                throw IOException("Mgeko returned an invalid catalog response")
            }
            val doc = Jsoup.parse(data.getString("results_html"), baseUrl)
            val entries = doc.select("article.comic-card").mapNotNull { card ->
                val link = card.selectFirst(".comic-card__title a[href^=/manga/]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                val title = link.text().trim()
                if (url.isBlank() || title.isBlank()) return@mapNotNull null
                SManga(url, title, thumbnailUrl = lazyImage(card.selectFirst(".comic-card__cover img")))
            }.distinctBy { it.url }
            return MangasPage(entries, data.getInt("page") < data.getInt("num_pages"))
        }

        fun parseChapters(html: String, baseUrl: String): List<SChapter> =
            Jsoup.parse(html, baseUrl).select("a[href^=/reader/]").mapNotNull { link ->
                val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = link.selectFirst(".chapter-title")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val number = Regex("""\d+(?:\.\d+)?""").find(label)?.value?.toFloatOrNull() ?: -1f
                SChapter(url, "Chapter $label", chapterNumber = number)
            }.distinctBy { it.url }
    }
}
