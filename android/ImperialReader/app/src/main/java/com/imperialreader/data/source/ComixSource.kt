package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class ComixSource : HttpSource() {
    override val id = "generic_comix_to"
    override val name = "Comix.to"
    override val lang = "en"
    override val baseUrl = "https://comix.to"

    override suspend fun getPopular(page: Int): MangasPage =
        browse("/browse?order%5Bscore%5D=desc&page=$page")

    override suspend fun getLatest(page: Int): MangasPage =
        browse("/browse?order%5Bchapter_updated_at%5D=desc&page=$page")

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        return browse("/browse?keyword=${enc(query)}&page=$page")
    }

    private suspend fun browse(path: String): MangasPage {
        val doc = page(path)
        val titles = linkedMapOf<String, SManga>()
        doc.select("a[href]").forEach { anchor ->
            val url = anchor.absUrl("href")
            val uri = runCatching { URI(url) }.getOrNull() ?: return@forEach
            if (!uri.host.equals(URI(baseUrl).host, ignoreCase = true) ||
                !TITLE_PATH.matches(uri.path.trimEnd('/'))
            ) return@forEach
            val image = anchor.selectFirst("img, [style*=background-image]")
                ?: anchor.parent()?.selectFirst("img, [style*=background-image]")
            val title = listOf(
                anchor.attr("title"),
                image?.attr("alt").orEmpty(),
                anchor.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
                anchor.text()
            ).map(String::trim).firstOrNull(String::isNotBlank) ?: return@forEach
            titles.putIfAbsent(
                uri.path,
                SManga(url = "$baseUrl${uri.path}", title = title, thumbnailUrl = imageUrl(image))
            )
        }
        val requestedPage = Regex("""(?:[?&])page=(\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nextPage = doc.select("a[href]").any { link ->
            val href = link.absUrl("href")
            val pageNumber = Regex("""(?:[?&])page=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            pageNumber == requestedPage + 1 ||
                link.text().trim().equals("next", ignoreCase = true) ||
                link.attr("rel").equals("next", ignoreCase = true)
        }
        val results = titles.values.toList()
        if (results.isEmpty() && requestedPage == 1) {
            val message = doc.body().text()
            if (!NO_RESULTS.any { message.contains(it, ignoreCase = true) }) {
                throw IOException("Comix did not return readable title results")
            }
        }
        return MangasPage(results, nextPage || results.size >= PAGE_SIZE)
    }
    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = page(manga.url)
        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        val links = doc.select("a[href]").toList()
        val authors = links.filter { it.attr("href").contains("/author/") }
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val artists = links.filter { it.attr("href").contains("/artist/") }
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val description = doc.selectFirst(
            ".description, .description-content, .summary, .synopsis, [itemprop=description]"
        )?.text()?.trim()?.ifBlank { null }
        val status = doc.selectFirst(".status, [data-status]")?.text().orEmpty()
        return manga.copy(
            title = title,
            thumbnailUrl = imageUrl(doc.selectFirst(".poster img, .cover img, .thumbnail img, [style*=background-image]"))
                ?: manga.thumbnailUrl,
            author = authors.joinToString(", ").ifBlank { manga.author },
            artist = artists.joinToString(", ").ifBlank { manga.artist },
            description = description ?: manga.description,
            genre = doc.select("a[href*=/genre/], a[href*=/tag/]")
                .map { it.text().trim() }.filter(String::isNotBlank).distinct()
                .joinToString(", ").ifBlank { manga.genre },
            status = statusFromText(status)
        )
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = page(manga.url)
        val mangaId = TITLE_PATH.matchEntire(URI(manga.url).path.trimEnd('/'))
            ?.groupValues?.get(1) ?: throw IOException("Invalid Comix title address")
        val chapters = doc.select("a[href]").mapNotNull { anchor ->
            val uri = runCatching { URI(anchor.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
            if (!uri.host.equals(URI(baseUrl).host, ignoreCase = true)) return@mapNotNull null
            val match = CHAPTER_PATH.matchEntire(uri.path.trimEnd('/')) ?: return@mapNotNull null
            if (match.groupValues[1] != mangaId) return@mapNotNull null
            val name = anchor.text().trim().ifBlank { "Chapter ${match.groupValues[3]}" }
            val time = anchor.parent()?.selectFirst("time")?.attr("datetime").orEmpty()
            SChapter(
                url = "$baseUrl${uri.path}",
                name = name,
                dateUpload = parseSiteDate(time),
                chapterNumber = match.groupValues[3].toFloatOrNull() ?: parseChapterNumber(name)
            )
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("Comix did not return a readable chapter list")
        return chapters.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = page(chapter.url)
        val reader = doc.selectFirst(
            ".chapter-content, .reader-content, .reading-content, .page-container, #chapter-container, #reader"
        ) ?: doc.body()
        val pages = reader.select("img, [data-src], [data-original], [style*=background-image]")
            .filterNot(::isNonPageImage)
            .mapNotNull(::imageUrl)
            .distinct()
        if (pages.isEmpty()) throw IOException("Comix did not return any readable chapter images")
        return pages
    }

    private suspend fun page(pathOrUrl: String): Document {
        val url = if (pathOrUrl.startsWith("http", ignoreCase = true)) {
            pathOrUrl
        } else {
            baseUrl + pathOrUrl
        }
        return Jsoup.parse(getString(url), url)
    }

    private fun imageUrl(element: Element?): String? {
        if (element == null) return null
        val value = listOf(
            "data-src", "data-original", "data-lazy-src", "data-image", "src"
        ).map { element.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            ?: element.attr("srcset").substringBefore(',').substringBefore(' ').trim()
                .takeIf(String::isNotBlank)
            ?: element.attr("data-srcset").substringBefore(',').substringBefore(' ').trim()
                .takeIf(String::isNotBlank)
            ?: Regex("""url\(['"]?([^)'"]+)""")
                .find(element.attr("style"))?.groupValues?.get(1)
        if (value.isNullOrBlank()) return null
        val absolute = element.ownerDocument()?.let { doc ->
            doc.baseUri().let { base ->
                when {
                    value.startsWith("//") -> "https:$value"
                    value.startsWith("http://") || value.startsWith("https://") -> value
                    else -> URI(base).resolve(value).toString()
                }
            }
        }
        val uri = absolute?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        return absolute.takeIf { uri.scheme == "http" || uri.scheme == "https" }
    }

    private fun isNonPageImage(element: Element): Boolean {
        val descriptor = (element.attr("class") + " " + element.attr("alt") + " " +
            element.attr("src") + " " + element.attr("data-src")).lowercase()
        if (listOf("avatar", "logo", "icon", "poster", "cover", "banner", "advert").any(descriptor::contains)) {
            return true
        }
        return element.parents().any { it.tagName() in setOf("header", "nav", "footer") }
    }

    private companion object {
        const val PAGE_SIZE = 24
        val NO_RESULTS = listOf("no results", "nothing found", "no titles found")
        val TITLE_PATH = Regex("""^/title/([a-z0-9]+)-[^/]+/?$""", RegexOption.IGNORE_CASE)
        val CHAPTER_PATH = Regex(
            """^/title/([a-z0-9]+)-[^/]+/([0-9]+)-chapter-([0-9]+(?:\.[0-9]+)?)(?:-[^/]+)?/?$""",
            RegexOption.IGNORE_CASE
        )
    }

}
