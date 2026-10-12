package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup

/** https://mangabuddy.com — written from the site's known layout; not verified live. */
class MangaBuddySource : HttpSource() {
    override val id = "mangabuddy"
    override val name = "MangaBuddy"
    override val lang = "en"
    override val baseUrl = "https://mangabuddy.com"

    override suspend fun getPopular(page: Int): MangasPage = list("$baseUrl/popular?page=$page")
    override suspend fun getLatest(page: Int): MangasPage = list("$baseUrl/latest?page=$page")
    override suspend fun search(query: String, page: Int): MangasPage = list("$baseUrl/search?q=${enc(query)}&page=$page")

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), baseUrl)
        val items = doc.select("div.book-item").mapNotNull { el ->
            val a = el.selectFirst("div.title h3 a, h3 a") ?: return@mapNotNull null
            SManga(url = a.absUrl("href"), title = a.text().trim(), thumbnailUrl = lazyImage(el.selectFirst("div.thumb img, img")))
        }
        return MangasPage(items.distinctBy { it.url }, items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("div.name h1, h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.description = doc.selectFirst("div.summary p.content, .summary .content")?.text()?.trim()?.ifBlank { null } ?: manga.description
        manga.thumbnailUrl = lazyImage(doc.selectFirst("div.img-cover img")) ?: manga.thumbnailUrl
        for (p in doc.select("div.meta p")) {
            val text = p.text()
            when {
                text.startsWith("Author", true) -> manga.author = text.substringAfter(':').trim().ifBlank { null }
                text.startsWith("Status", true) -> manga.status = statusFromText(text)
                text.startsWith("Genres", true) -> manga.genre = p.select("a").joinToString(", ") { it.text().trim().trim(',') }.ifBlank { null }
            }
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        val doc = Jsoup.parse(getString("$baseUrl/api/manga/$slug/chapters?source=detail"), baseUrl)
        return doc.select("li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val name = li.selectFirst("strong.chapter-title, .chapter-title")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
            if (name.isBlank()) return@mapNotNull null
            SChapter(
                url = a.absUrl("href"),
                name = name,
                dateUpload = parseSiteDate(li.selectFirst("time.chapter-update, .chapter-update")?.text().orEmpty()),
                chapterNumber = parseChapterNumber(name)
            )
        }.distinctBy { it.url }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        val raw = Regex("""var\s+chapImages\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        if (raw != null) return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.map { absoluteUrl(it) }
        return Jsoup.parse(html, chapter.url).select("#chapter-images img").mapNotNull { lazyImage(it) }
    }
}
