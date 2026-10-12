package com.imperialreader.data.book

import android.net.Uri
import com.imperialreader.App
import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.imperialreader.data.source.GenericSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.hostId
import org.json.JSONArray
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** One downloadable EPUB from an online catalog. */
data class BookItem(
    val sourceId: String,
    val sourceName: String,
    val id: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val epubUrl: String?,
    val description: String? = null
) {
    val key: String get() = "$sourceId:$id"
}

data class NovelChapter(val title: String, val url: String)

interface BookSource {
    val id: String
    val name: String

    /** Short label shown in the Extensions list. */
    val description: String get() = ""

    /** true = a web-novel site (chapters are text pages that get packed into an EPUB when you download). */
    val isWebNovel: Boolean get() = false

    /** Whether Global search should also query this catalog. */
    val inGlobalSearch: Boolean get() = true

    /** Web novels: chapter list in READING order (oldest first). */
    suspend fun chapters(item: BookItem): List<NovelChapter> = emptyList()

    /** Web novels: the readable HTML of one chapter. */
    suspend fun chapterHtml(chapter: NovelChapter): String = throw IOException("Not a web-novel source")
    suspend fun popular(page: Int): List<BookItem>
    suspend fun search(query: String, page: Int): List<BookItem>

    /** Direct link to the .epub file (some catalogs need a second request to find it). */
    suspend fun resolveEpubUrl(item: BookItem): String =
        item.epubUrl ?: throw IOException("No EPUB available for this title")
}

internal suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    App.instance.network.client.await(request).use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        resp.body?.string().orEmpty()
    }
}

internal suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
    App.instance.network.client.await(request).use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        resp.body?.string().orEmpty()
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Project Gutenberg through the public Gutendex API: public-domain classics, real EPUB files. */
class GutenbergSource : BookSource {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
    override val description = "EPUB library • public-domain classics"

    override suspend fun popular(page: Int) = parse(fetchText("https://gutendex.com/books/?page=$page&mime_type=application%2Fepub"))

    override suspend fun search(query: String, page: Int) =
        parse(fetchText("https://gutendex.com/books/?search=${enc(query)}&page=$page&mime_type=application%2Fepub"))

    private fun flip(name: String) =
        if (',' in name) name.substringAfter(',').trim() + " " + name.substringBefore(',').trim() else name

    private fun parse(json: String): List<BookItem> {
        val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val formats = o.optJSONObject("formats") ?: return@mapNotNull null
            val epub = formats.optString("application/epub+zip").ifBlank { null } ?: return@mapNotNull null
            val authors = o.optJSONArray("authors")
            val author = if (authors != null && authors.length() > 0) flip(authors.getJSONObject(0).optString("name")) else null
            val summary = o.optJSONArray("summaries")?.optString(0)?.ifBlank { null }
            BookItem(
                sourceId = id, sourceName = name, id = o.optInt("id").toString(),
                title = o.optString("title"), author = author,
                coverUrl = formats.optString("image/jpeg").ifBlank { null },
                epubUrl = epub, description = summary
            )
        }
    }
}

/** Internet Archive: openly downloadable EPUBs (lending-restricted books are excluded). */
class InternetArchiveSource : BookSource {
    override val id = "archive"
    override val name = "Internet Archive"
    override val description = "EPUB library • openly downloadable books"

    override suspend fun popular(page: Int) = query("", page)
    override suspend fun search(query: String, page: Int) = query(query, page)

    private suspend fun query(text: String, page: Int): List<BookItem> {
        val base = "mediatype:texts AND format:EPUB AND -access-restricted-item:true"
        val q = if (text.isBlank()) base else "($text) AND $base"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=description" +
            "&sort%5B%5D=downloads+desc&rows=20&page=$page&output=json"
        val docs = JSONObject(fetchText(url)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val identifier = d.getString("identifier")
            BookItem(
                sourceId = id, sourceName = name, id = identifier,
                title = firstString(d.opt("title")) ?: identifier,
                author = firstString(d.opt("creator")),
                coverUrl = "https://archive.org/services/img/$identifier",
                epubUrl = null,
                description = firstString(d.opt("description"))?.replace(Regex("<[^>]*>"), " ")?.trim()
            )
        }
    }

    private fun firstString(v: Any?): String? = when (v) {
        is String -> v.ifBlank { null }
        is JSONArray -> if (v.length() > 0) v.optString(0).ifBlank { null } else null
        else -> null
    }

    override suspend fun resolveEpubUrl(item: BookItem): String {
        val files = JSONObject(fetchText("https://archive.org/metadata/${item.id}")).optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val name = files.getJSONObject(i).optString("name")
            if (name.endsWith(".epub", ignoreCase = true)) {
                val path = name.split('/').joinToString("/") { Uri.encode(it) }
                return "https://archive.org/download/${item.id}/$path"
            }
        }
        throw IOException("No EPUB file found for this item")
    }
}

/** Royal Road: original web fiction. Chapters are packed into an EPUB when you download. */
class RoyalRoadSource : BookSource {
    override val id = "royalroad"
    override val name = "Royal Road"
    override val description = "Web novel • original web fiction"
    override val isWebNovel = true
    private val base = "https://www.royalroad.com"

    override suspend fun popular(page: Int) = list("$base/fictions/best-rated?page=$page")
    override suspend fun search(query: String, page: Int) = list("$base/fictions/search?title=${enc(query)}&page=$page")

    private suspend fun list(url: String): List<BookItem> {
        val doc = Jsoup.parse(fetchHtml(url), base)
        return doc.select("div.fiction-list-item").mapNotNull { el ->
            val a = el.selectFirst("h2.fiction-title a") ?: return@mapNotNull null
            BookItem(
                sourceId = id, sourceName = name, id = a.absUrl("href"), title = a.text().trim(), author = null,
                coverUrl = el.selectFirst("img")?.absUrl("src")?.ifBlank { null }, epubUrl = null,
                description = el.selectFirst("div[id^=description]")?.text()?.take(400)
            )
        }
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val doc = Jsoup.parse(fetchHtml(item.id), item.id)
        return doc.select("table#chapters a[href*=/chapter/]")
            .map { NovelChapter(it.text().trim(), it.absUrl("href")) }
            .distinctBy { it.url }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val doc = Jsoup.parse(fetchHtml(chapter.url), chapter.url)
        return doc.selectFirst("div.chapter-content")?.html() ?: throw IOException("Chapter text not found")
    }
}

/** Any other web-novel site, read with the pattern-based scraper (best effort; some sites need a browser). */
class WebNovelSource(
    override val id: String,
    override val name: String,
    baseUrl: String
) : BookSource {
    override val description = "Web novel • best-effort"
    override val isWebNovel = true
    private val engine = GenericSource(name, baseUrl, "en", false, novel = true)

    private fun toItem(m: SManga) = BookItem(
        sourceId = id, sourceName = name, id = m.url, title = m.title, author = null,
        coverUrl = m.thumbnailUrl, epubUrl = null
    )

    override suspend fun popular(page: Int) = engine.getPopular(page).mangas.map { toItem(it) }
    override suspend fun search(query: String, page: Int) = engine.search(query, page).mangas.map { toItem(it) }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val newestFirst = engine.getChapters(SManga(url = item.id, title = item.title))
        return newestFirst.reversed().map { NovelChapter(it.name, it.url) }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String = engine.chapterText(chapter.url)
}

object BookSources {
    /** Web-novel sites from the QuickNovel source list. Domains can change; edit here if one moves. */
    private val novelSites = listOf(
        "AllNovel" to "https://allnovel.org", "Bright Novel" to "https://brightnovels.com",
        "Chikari" to "https://chikari.net", "Chrysanthemum Garden" to "https://chrysanthemumgarden.com",
        "FaqWiki" to "https://faqwiki.us", "FenrirRealm" to "https://fenrirealm.com",
        "FreeWebNovel" to "https://freewebnovel.com", "Graycity" to "https://graycity.net",
        "Hiraeth Translation" to "https://hiraethtranslation.com", "LibRead" to "https://libread.com",
        "Light Novel Translations" to "https://lightnovelstranslations.com", "LnMTL" to "https://lnmtl.com",
        "Luxon Scan" to "https://luxonscan.com", "MtlNovel" to "https://www.mtlnovel.com",
        "Novel Buddy" to "https://novelbuddy.com", "Novel Light" to "https://novellight.net",
        "Novel Phoenix" to "https://novelphoenix.com", "NovelBin" to "https://novelbin.me",
        "NovelFire" to "https://novelfire.net", "NovelFull" to "https://novelfull.com",
        "PawRead" to "https://pawread.com", "Ranovel" to "https://ranovel.com",
        "ReadFrom.Net" to "https://readfrom.net", "ReadHive" to "https://readhive.org",
        "Scribblehub" to "https://www.scribblehub.com", "ScrollersPub" to "https://scrollers.pub",
        "SonicMTL" to "https://sonicmtl.com", "WTR-LAB" to "https://wtr-lab.com",
        "Wattpad" to "https://www.wattpad.com", "WuxiaBox" to "https://www.wuxiabox.com",
        "WuxiaClick" to "https://wuxia.click", "WuxiaWorld" to "https://www.wuxiaworld.com"
    )

    val all: List<BookSource> =
        listOf(GutenbergSource(), InternetArchiveSource(), RoyalRoadSource()) +
            novelSites.map { (name, url) -> WebNovelSource("novel_" + hostId(url, name), name, url) }
    fun get(id: String): BookSource? = all.firstOrNull { it.id == id }
}
