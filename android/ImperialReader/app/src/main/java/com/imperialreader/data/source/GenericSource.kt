package com.imperialreader.data.source

import com.imperialreader.data.network.CloudflareException
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/**
 * Best-effort scraper for sites that use neither Madara nor MangaThemesia (custom engines, Astro/Next sites,
 * comic blogs …). It finds listing pages, series cards, chapter links and page images by pattern instead of
 * by site-specific selectors, so it works on many sites but is not guaranteed on any given one.
 */
class GenericSource(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
    override val nsfw: Boolean = false,
    private val novel: Boolean = false
) : HttpSource() {

    override val id: String = "generic_" + hostId(baseUrl, name)

    @Volatile private var listPattern: String? = null
    @Volatile private var latestPattern: String? = null
    @Volatile private var searchPattern: String? = null

    private val novelList = listOf(
        "/popular?page={p}", "/most-popular?page={p}", "/novel-list?page={p}", "/novels?page={p}", "/novel?page={p}",
        "/list/best-rated?page={p}", "/ranking?page={p}", "/library?page={p}"
    )
    private val listCandidates = (if (novel) novelList else emptyList()) + listOf(
        "/series?sort=popular&page={p}", "/webtoons?page={p}", "/manga?page={p}", "/manga/page/{p}", "/comics?page={p}", "/browse?page={p}",
        "/series?page={p}", "/manga-list?page={p}", "/comic-list?page={p}", "/all-comics?page={p}",
        "/page/{p}", "/?page={p}", "/?paged={p}"
    )
    private val latestCandidates = listOf("/latest?page={p}", "/latest-updates?page={p}", "/latest/page/{p}", "/page/{p}")
    private val searchCandidates = (if (novel) listOf(
        "/search?keyword={q}&page={p}", "/search?q={q}&page={p}", "/search?query={q}&page={p}",
        "/search?s={q}&page={p}", "/search?name={q}&page={p}", "/?s={q}&paged={p}"
    ) else emptyList()) + listOf(
        "/?s={q}&paged={p}", "/search?q={q}&page={p}", "/search?keyword={q}&page={p}",
        "/search?s={q}&page={p}", "/search?query={q}&page={p}", "/?s={q}&post_type=wp-manga",
        "/series?search={q}&page={p}", "/series?q={q}&page={p}", "/search?name={q}&page={p}"
    )

    private val badPath = Regex(
        "(?i)(^|/)(genres?|tags?|categor(y|ies)|page|login|register|chapters?|episodes?|issues?|authors?|search|feed|" +
            "privacy-policy|terms|dmca|contact-us|about-us|bookmarks|wp-content|wp-json|cdn-cgi|completed|raw" +
            (if (novel) "" else "|novels?|ebooks?") + ")(/|$)"
    )
    private val chapterRegex = Regex("(?i)(chapter|chap\\.?|ch\\.?|episode|ep\\.?|issue|#)\\s*[-_ ]?\\d")
    private val chapterHref = Regex("(?i)[-/_](chapter|chap|episode|ep|issue|ch)[-_/]?\\d")

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.lowercase()

    private suspend fun doc(url: String): Document = Jsoup.parse(getString(url), url)

    // ------------------------------------------------------------------ listings

    override suspend fun getPopular(page: Int): MangasPage = discoverList(page, latest = false)
    override suspend fun getLatest(page: Int): MangasPage = discoverList(page, latest = true)

    private suspend fun discoverList(page: Int, latest: Boolean): MangasPage {
        val known = if (latest) latestPattern ?: listPattern else listPattern
        if (known != null) return cardsOf(page, known)
        val candidates = if (latest) latestCandidates + listCandidates else listCandidates
        var last: Exception? = null
        for (c in candidates) {
            try {
                val result = cardsOf(1, c)
                if (result.mangas.size >= 4) {
                    if (latest && c in latestCandidates) latestPattern = c else listPattern = c
                    return if (page == 1) result else cardsOf(page, c)
                }
            } catch (e: CloudflareException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No series listing found on this site")
    }

    private suspend fun cardsOf(page: Int, pattern: String): MangasPage {
        val url = baseUrl.trimEnd('/') + pattern.replace("{p}", page.toString())
        val cards = try {
            parseCards(doc(url))
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) emptyList() else throw e
        }
        return MangasPage(cards, cards.isNotEmpty())
    }

    /** Series cards = same-site links that wrap a cover image; the most common first path segment wins. */
    private fun parseCards(doc: Document): List<SManga> {
        val site = host(baseUrl)
        data class Card(val segment: String, val manga: SManga)
        val all = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (href.isBlank() || host(href) != site) return@mapNotNull null
            val path = runCatching { URI(href).path }.getOrNull().orEmpty().trim('/')
            if (path.isBlank() || path.count { it == '/' } > 2 || badPath.containsMatchIn(path)) return@mapNotNull null
            if (a.parents().any { it.tagName() in setOf("nav", "header", "footer") }) return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = listOf(a.attr("title"), img.attr("alt"), a.text(), a.parent()?.selectFirst("h1,h2,h3,h4,.title")?.text().orEmpty())
                .map { it.trim() }.firstOrNull { it.length >= 2 } ?: return@mapNotNull null
            Card(path.substringBefore('/'), SManga(url = href, title = title.take(150), thumbnailUrl = lazyImage(img)))
        }
        val dominant = all.groupBy { it.segment }.maxByOrNull { it.value.size }?.key ?: return emptyList()
        return all.filter { it.segment == dominant }.map { it.manga }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val first = query.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val firstWant = normalizeTitle(first)
        val novelTerms = Regex("""[\p{L}\p{N}]+""").findAll(query.lowercase())
            .map { normalizeTitle(it.value) }
            .filter { it.length > 1 && it !in setOf("novel", "novels", "online") }
            .distinct()
            .toList()
        val novelWant = normalizeTitle(query)
        fun matches(r: MangasPage, evidence: String): Boolean {
            if (!novel) return firstWant.isBlank() || r.mangas.any { normalizeTitle(it.title).contains(firstWant) }
            val pageEvidence = normalizeTitle(evidence)
            return (novelWant.isNotBlank() && pageEvidence.contains(novelWant)) ||
                (novelTerms.isNotEmpty() && r.mangas.any { manga ->
                    val title = normalizeTitle(manga.title)
                    novelTerms.all { term -> title.contains(term) }
                })
        }
        suspend fun run(pattern: String): Pair<MangasPage, String> {
            val url = baseUrl.trimEnd('/') + pattern.replace("{q}", enc(query)).replace("{p}", page.toString())
            val d = doc(url)
            return MangasPage(parseCards(d), true) to
                (d.title() + " " + d.selectFirst("h1,h2")?.text().orEmpty())
        }
        searchPattern?.let { pattern ->
            try {
                val (result, evidence) = run(pattern)
                if (!novel || matches(result, evidence)) return result
                searchPattern = null
            } catch (e: CloudflareException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                searchPattern = null
            }
        }
        for (c in searchCandidates) {
            try {
                val (r, evidence) = run(c)
                if (r.mangas.isNotEmpty() && matches(r, evidence)) {
                    searchPattern = c
                    return r
                }
            } catch (e: CloudflareException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return MangasPage(emptyList(), false)
    }

    // ------------------------------------------------------------------ details & chapters

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val d = doc(manga.url)
        manga.title = d.selectFirst("h1")?.text()?.trim()?.takeIf { it.length >= 2 }
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() }
            ?: manga.title
        manga.thumbnailUrl = d.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() } ?: manga.thumbnailUrl
        manga.description = d.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.length > 30 }
            ?: d.select("p").map { it.text().trim() }.filter { it.length > 80 }.maxByOrNull { it.length }
            ?: manga.description
        manga.genre = d.select("a[href*=genre], a[href*=/tag], a[href*=categor]").map { it.text().trim().trim(',') }
            .filter { it.isNotBlank() && it.length < 25 }.distinct().take(10).joinToString(", ").ifBlank { null }
        fun labeled(label: String): String? = d.select("li, p, div, span, td, tr").asSequence()
            .map { it.ownText().ifBlank { it.text() }.trim() }
            .firstOrNull { it.length < 140 && it.startsWith(label, ignoreCase = true) && it.length > label.length }
            ?.substring(label.length)?.trim(' ', ':', '-')?.ifBlank { null }
        manga.author = labeled("Author") ?: labeled("Authors")
        manga.artist = labeled("Artist")
        manga.status = statusFromText(labeled("Status").orEmpty())
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val site = host(manga.url)
        val mangaPath = runCatching { URI(manga.url).path }.getOrNull().orEmpty().trimEnd('/')
        val found = LinkedHashMap<String, SChapter>()

        fun collect(d: Document) {
            for (a in d.select("a[href]")) {
                val href = a.absUrl("href")
                if (href.isBlank() || host(href) != site) continue
                val path = runCatching { URI(href).path }.getOrNull().orEmpty().trimEnd('/')
                if (path == mangaPath || path.isBlank()) continue
                val text = a.text().trim()
                if (!(chapterRegex.containsMatchIn(text) || chapterHref.containsMatchIn(path))) continue
                if (a.parents().any { it.tagName() in setOf("nav", "header", "footer") }) continue
                val name = text.ifBlank { a.attr("title").trim() }.ifBlank { path.substringAfterLast('/').replace('-', ' ') }
                val dateText = Regex("""\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},\s+\d{4}""").find(a.parent()?.text().orEmpty())?.value.orEmpty()
                found.putIfAbsent(
                    href,
                    SChapter(url = href, name = name.take(120), dateUpload = parseSiteDate(dateText), chapterNumber = parseChapterNumber(name))
                )
            }
        }

        var d = doc(manga.url)
        collect(d)
        if (novel) {
            // novel sites paginate long chapter lists
            var pages = 0
            while (pages < 40) {
                val next = d.selectFirst("a[rel=next], ul.pagination a:matchesOwn(^(Next|›|»|>)\$), a.next")
                    ?.absUrl("href")?.takeIf { it.isNotBlank() && it != d.location() } ?: break
                d = try { doc(next) } catch (e: Exception) { break }
                val before = found.size
                collect(d)
                if (found.size == before) break
                pages++
            }
        }
        // newest first: highest chapter number first, unnumbered ones keep page order at the end
        val list = found.values.toList()
        return list.filter { it.chapterNumber >= 0f }.sortedByDescending { it.chapterNumber } + list.filter { it.chapterNumber < 0f }
    }

    /** Readable HTML of a novel chapter: the container with the most paragraph text. */
    suspend fun chapterText(url: String): String {
        val d = doc(url)
        d.select("script, style, noscript, iframe, nav, header, footer, aside, form, .ads, .ad, [class*=advert], [id*=advert]").remove()
        val best = d.select("div, article, section, main").maxByOrNull { el ->
            el.children().asSequence().filter { it.tagName() == "p" }.sumOf { it.text().length }
        }
        val html = best?.html().orEmpty()
        if (best == null || best.select("p").size < 2) throw IOException("Couldn't find the chapter text on this page")
        return html
    }

    // ------------------------------------------------------------------ pages

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        val d = Jsoup.parse(html, chapter.url)
        val bad = Regex("(?i)(logo|banner|avatar|icon|sprite|/ads?[/_-]|loading|placeholder|thumb|emoji|gravatar)")
        val imgExt = Regex("(?i)\\.(jpe?g|png|webp|gif|avif)(\\?.*)?$")

        fun good(img: Element): String? {
            val u = lazyImage(img) ?: return null
            if (!imgExt.containsMatchIn(u) || bad.containsMatchIn(u) || bad.containsMatchIn(img.className())) return null
            if (img.parents().any { it.tagName() in setOf("nav", "header", "footer", "aside") }) return null
            return u
        }

        // the container holding the most qualifying images is the reader
        val byParent = d.select("img").mapNotNull { img -> good(img)?.let { img.parent() to it } }
            .groupBy({ it.first }, { it.second })
        val best = byParent.maxByOrNull { it.value.size }?.value.orEmpty()
        if (best.size >= 2) return best.distinct()

        // fallback: image URLs embedded in scripts, keeping the most common folder
        val urls = Regex("https?:\\\\?/\\\\?/[^\"'\\s\\\\]+?\\.(?:jpe?g|png|webp|avif)", RegexOption.IGNORE_CASE)
            .findAll(d.select("script").joinToString("\n") { it.data() })
            .map { it.value.replace("\\/", "/") }.filter { !bad.containsMatchIn(it) }.toList()
        val folder = urls.groupBy { it.substringBeforeLast('/') }.maxByOrNull { it.value.size }?.value.orEmpty()
        if (folder.size >= 2) return folder.distinct()
        return best
    }
}
