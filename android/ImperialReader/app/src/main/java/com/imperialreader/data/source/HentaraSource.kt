package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URI

class HentaraSource : HttpSource() {
    override val id = "wp_hentara_com"
    override val name = "Hentara"
    override val lang = "en"
    override val baseUrl = "https://hentara.com"
    override val nsfw = true

    @Volatile
    private var catalog: List<SManga>? = null

    override suspend fun getPopular(page: Int): MangasPage =
        catalogPage("$baseUrl/popular-manhwa", page)

    override suspend fun getLatest(page: Int): MangasPage =
        catalogPage("$baseUrl/latest-manhwa", page)

    private suspend fun catalogPage(url: String, page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(getString(url), url)
        val items = parseCatalog(doc)
        if (items.isEmpty()) throw IOException("Hentara returned no readable manhwa titles")
        return MangasPage(items, false)
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val terms = normalizeTitle(query).takeIf { it.isNotBlank() } ?: return getPopular(page)
        val entries = catalog ?: loadCatalog().also { catalog = it }
        val matches = entries.filter { normalizeTitle(it.title).contains(terms) }
        if (matches.isEmpty()) return MangasPage(emptyList(), false)
        val pageSize = 24
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        return MangasPage(matches.drop(offset).take(pageSize), offset + pageSize < matches.size)
    }

    private suspend fun loadCatalog(): List<SManga> {
        val doc = Jsoup.parse(getString("$baseUrl/sitemap-comics.xml"), baseUrl, Parser.xmlParser())
        val entries = doc.select("url").mapNotNull { url ->
            val address = url.selectFirst("loc")?.text()?.trim().orEmpty()
            if (runCatching { URI(address).host }.getOrNull() != URI(baseUrl).host) return@mapNotNull null
            val path = runCatching { URI(address).path }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
            val slug = Regex("""^/manhwa/([^/]+)$""").matchEntire(path)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = slug.replace('-', ' ').split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            val cover = url.getElementsByTag("image:loc").firstOrNull()?.text()?.trim()
            SManga(address, title, cover?.ifBlank { null })
        }.distinctBy { it.url }
        if (entries.isEmpty()) throw IOException("Hentara sitemap returned no manhwa titles")
        return entries
    }

    private fun parseCatalog(doc: org.jsoup.nodes.Document): List<SManga> =
        doc.select("#ssr-content a.pg-card[href]").mapNotNull { a ->
            val path = runCatching { URI(a.absUrl("href")).path }.getOrNull()?.trimEnd('/')
                ?: return@mapNotNull null
            if (!Regex("""^/manhwa/[^/]+$""").matches(path)) return@mapNotNull null
            val title = a.selectFirst(".pg-card-title")?.text()?.trim()?.ifBlank { null }
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            SManga(a.absUrl("href"), title, a.selectFirst("img")?.let(::lazyImage))
        }.distinctBy { it.url }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst(".ssr-description")?.text()?.trim()?.ifBlank { null }
            ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val seriesPath = runCatching { URI(manga.url).path }.getOrNull()?.trimEnd('/')
            ?: throw IOException("Hentara returned an invalid manhwa address")
        val chapterPath = Regex("""^${Regex.escape(seriesPath)}/chapter-(\d+(?:-\d+)?)$""", RegexOption.IGNORE_CASE)
        val chapters = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (runCatching { URI(href).host }.getOrNull() != URI(baseUrl).host) return@mapNotNull null
            val path = runCatching { URI(href).path }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
            val match = chapterPath.matchEntire(path) ?: return@mapNotNull null
            val numberText = match.groupValues[1].replace('-', '.')
            val number = numberText.toFloatOrNull() ?: return@mapNotNull null
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            val name = label.takeIf { parseChapterNumber(it) == number } ?: "Chapter $numberText"
            val date = a.selectFirst("time")?.let { it.attr("datetime").ifBlank { it.text() } }.orEmpty()
            SChapter(href, name, parseSiteDate(date), number)
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("Hentara returned no valid chapters for this manhwa")
        return chapters.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val images = doc.select("img[src], img[data-src], img[data-lazy-src]")
            .mapNotNull(::lazyImage)
            .filter { image ->
                val uri = runCatching { URI(image) }.getOrNull()
                uri?.host == "cdn.hentara.com" && "/chapter-" in uri.path
            }
            .distinct()
        if (images.isEmpty()) throw IOException("Hentara returned no readable pages for this chapter")
        return images
    }
}
