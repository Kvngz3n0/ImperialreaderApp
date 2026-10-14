package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class DemonicScansSource : HttpSource() {
    override val id = "wp_demonicscans_org"
    override val name = "DemonicScans"
    override val lang = "en"
    override val baseUrl = "https://demonicscans.org"

    override suspend fun getPopular(page: Int): MangasPage =
        mangaList("$baseUrl/advanced.php?list=${page.coerceAtLeast(1)}&status=all&orderby=VIEWS+DESC", page, false)

    override suspend fun getLatest(page: Int): MangasPage =
        mangaList("$baseUrl/lastupdates.php?list=${page.coerceAtLeast(1)}", page, true)

    override suspend fun search(query: String, page: Int): MangasPage {
        val url = "$baseUrl/search.php?manga=${enc(query)}"
        val doc = Jsoup.parse(getString(url), url)
        val mangas = doc.select("body > a[href]").mapNotNull { parseManga(it, searchResult = true) }
            .distinctBy { it.url }
        if (mangas.isEmpty() && page <= 1) throw IOException("DemonicScans returned no readable search results")
        return MangasPage(mangas, mangas.isNotEmpty() && hasNextPage(doc, page))
    }

    private suspend fun mangaList(url: String, page: Int, latest: Boolean): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val selector = if (latest) {
            "#updates-container > .updates-element"
        } else {
            "#advanced-content > .advanced-element"
        }
        val mangas = doc.select(selector).mapNotNull { card ->
            if (latest) {
                parseManga(card, searchResult = false, latest = true)
            } else {
                parseManga(card, searchResult = false)
            }
        }.distinctBy { it.url }
        if (mangas.isEmpty() && page <= 1) throw IOException("DemonicScans returned no readable titles")
        return MangasPage(mangas, mangas.isNotEmpty() && hasNextPage(doc, page))
    }

    private fun parseManga(element: Element, searchResult: Boolean, latest: Boolean = false): SManga? {
        val anchor = if (latest) {
            element.selectFirst(".updates-element-info a[href]")
        } else if (searchResult) {
            element
        } else {
            element.selectFirst("a[href]")
        } ?: return null
        val href = absoluteUrl(anchor.attr("href").trim())
        if (runCatching { URI(href).host }.getOrNull() != URI(baseUrl).host) return null
        val title = when {
            latest -> anchor.ownText().trim().ifBlank { anchor.text().trim() }
            searchResult -> element.selectFirst(".seach-right > div")?.text()?.trim().orEmpty()
            else -> element.selectFirst("h1")?.ownText()?.trim().orEmpty()
        }.ifBlank { anchor.attr("title").trim() }
        if (title.isBlank()) return null
        val image = if (latest) element.selectFirst(".thumb img") else anchor.selectFirst("img")
        return SManga(url = href, title = title, thumbnailUrl = lazyImage(image))
    }

    private fun hasNextPage(doc: org.jsoup.nodes.Document, currentPage: Int): Boolean =
        doc.select("a[href]").any { link ->
            val target = runCatching { URI(link.absUrl("href")) }.getOrNull() ?: return@any false
            val page = target.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("list=") }
                ?.substringAfter('=')
                ?.toIntOrNull()
            page != null && page > currentPage
        }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val info = doc.selectFirst("#manga-info-container")
            ?: throw IOException("DemonicScans did not return manga details")
        manga.title = info.selectFirst("h1.big-fat-titles")?.ownText()?.trim()
            ?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = info.selectFirst("#manga-page img")?.let(::lazyImage) ?: manga.thumbnailUrl
        manga.description = info.selectFirst("#manga-info-rightColumn .white-font")?.text()?.trim()
            ?.ifBlank { null } ?: manga.description
        manga.author = info.selectFirst("#manga-info-stats div:has(> li:first-child:matchesOwn(Author)) > li:nth-child(2)")
            ?.text()?.trim()?.ifBlank { null } ?: manga.author
        manga.genre = info.select("div.genres-list > li").joinToString(", ") { it.text().trim() }
            .ifBlank { manga.genre.orEmpty() }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val chapters = doc.select("#chapters-list a.chplinks[href]").mapNotNull { link ->
            val url = absoluteUrl(link.attr("href").trim())
            if (runCatching { URI(url).host }.getOrNull() != URI(baseUrl).host) return@mapNotNull null
            val name = link.ownText().trim().ifBlank { link.text().trim() }
            if (name.isBlank()) return@mapNotNull null
            val number = parseChapterNumber(name)
            SChapter(
                url = url,
                name = name,
                dateUpload = parseSiteDate(link.selectFirst("span")?.text().orEmpty()),
                chapterNumber = number
            )
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("DemonicScans returned no valid chapters for this title")
        return chapters.sortedWith(
            compareByDescending<SChapter> { it.chapterNumber >= 0f }
                .thenByDescending { it.chapterNumber }
                .thenByDescending { it.dateUpload }
        )
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("div > img.imgholder")
            .mapNotNull(::lazyImage)
            .distinct()
        if (pages.isEmpty()) throw IOException("DemonicScans returned no readable pages for this chapter")
        return pages
    }
}
