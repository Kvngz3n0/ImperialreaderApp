package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

class MangaTownSource : HttpSource() {
    override val id = "mangatown_mobile"
    override val name = "MangaTown"
    override val lang = "en"
    override val baseUrl = "https://m.mangatown.com"

    override suspend fun getPopular(page: Int): MangasPage = listing(if (page <= 1) "$baseUrl/hot" else "$baseUrl/hot/$page.html")
    override suspend fun getLatest(page: Int): MangasPage = listing(if (page <= 1) "$baseUrl/latest" else "$baseUrl/latest/$page.html")
    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        val path = if (page <= 1) "/search" else "/search/$page.htm"
        return listing("$baseUrl$path?name=${enc(query.trim())}")
    }

    private suspend fun listing(url: String): MangasPage = parseListing(getString(url), url)

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst(".manga-detail-top .title")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        secureImage(lazyImage(doc.selectFirst("img.detail-cover")))?.let { manga.thumbnailUrl = it }
        doc.selectFirst(".detail-info a[href^=/author/]")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.author = it }
        doc.selectFirst(".detail-info a[href^=/artist/]")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.artist = it }
        val genres = extractGenreTags(doc)
        if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val chapters = parseChapters(getString(manga.url), manga.url)
        if (chapters.isEmpty()) throw IOException("MangaTown returned no chapters for ${manga.title}")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("img.image").mapNotNull { secureImage(lazyImage(it)) }.distinct()
        if (pages.isEmpty()) throw IOException("MangaTown returned no pages for ${chapter.name}")
        return pages
    }

    companion object {
        private fun secureImage(url: String?): String? =
            url?.let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }

        fun parseListing(html: String, url: String): MangasPage {
            val doc = Jsoup.parse(html, url)
            val entries = doc.select(".post-list .post").mapNotNull { card ->
                val link = card.selectFirst("a.manga-cover[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val title = card.selectFirst(".post-info .title")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                SManga(href, title, thumbnailUrl = secureImage(lazyImage(link.selectFirst("img"))))
            }.distinctBy { it.url }
            val next = doc.select("a[href]").any { it.text().trim().equals("NEXT", true) }
            return MangasPage(entries, next)
        }

        fun parseChapters(html: String, url: String): List<SChapter> {
            val doc: Document = Jsoup.parse(html, url)
            return doc.select(".detail-ch-list a[href]").mapNotNull { link ->
                val href = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = link.text().trim()
                val number = Regex("""(?i)\bC\.\s*(\d+(?:\.\d+)?)""").find(label)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                SChapter(href, "Chapter $number", chapterNumber = number.toFloatOrNull() ?: -1f)
            }.distinctBy { it.url }
        }
    }
}
