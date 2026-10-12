package com.imperialreader.data.source

import com.imperialreader.data.network.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/**
 * Site-specific gallery adapters. These sites store a complete gallery as one source chapter so the
 * existing chapter list, offline download queue, reading progress, and library UI continue to work.
 */
class HentaiDbSource : HttpSource() {
    override val id = "hentaidb_xyz"
    override val name = "HentaiDB"
    override val lang = "en"
    override val baseUrl = "https://hentaidb.xyz"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/comics${if (page > 1) "?p=$page" else ""}")

    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val suffix = buildString {
            append("?q=").append(enc(query))
            if (page > 1) append("&p=").append(page)
        }
        return list("$baseUrl/comics$suffix")
    }

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val results = doc.select("a.ca[href]")
            .filter { it.attr("href").startsWith("/comics/") }
            .filter { it.selectFirst("img") != null }
            .mapNotNull { a ->
                val href = a.absUrl("href").takeIf { it.startsWith("$baseUrl/comics/") } ?: return@mapNotNull null
                val title = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }.trim()
                if (title.isBlank()) return@mapNotNull null
                SManga(url = href, title = title, thumbnailUrl = imageUrl(a.selectFirst("img")))
            }.distinctBy { it.url }
        return MangasPage(results, results.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("img[alt*=cover]")?.let(::imageUrl) ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.ifBlank { null } ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val read = doc.selectFirst("#readB[href]")?.absUrl("href")
            ?: throw IOException("HentaiDB did not provide a reader link for this gallery")
        val pages = Regex("""(?i)(\d+)\s+pages?""").find(doc.selectFirst("#readM")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        return listOf(SChapter(read, if (pages != null) "Gallery ($pages pages)" else "Gallery", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("img[alt^=Page]").mapNotNull(::imageUrl).distinct()
        if (pages.isEmpty()) throw IOException("HentaiDB returned the reader page but no page images")
        return pages
    }
}

/** HTML gallery adapter for the nhentai.xxx domain. */
class NhentaiXxxSource : HttpSource() {
    override val id = "nhentai_xxx"
    override val name = "nhentai.xxx"
    override val lang = "en"
    override val baseUrl = "https://nhentai.xxx"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage =
        list(if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page")
    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)
    override suspend fun search(query: String, page: Int): MangasPage = list(pageUrl(query, page))

    private fun pageUrl(query: String, page: Int): String =
        "$baseUrl/search/?key=${enc(query)}&page=$page"

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val results = doc.select("a[href]")
            .filter { it.attr("href").startsWith("/g/") }
            .filter { it.selectFirst("img") != null }
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val id = galleryId(href) ?: return@mapNotNull null
                val img = a.selectFirst("img")
                val title = a.attr("title").ifBlank {
                    img?.attr("alt").orEmpty().ifBlank { a.text() }
                }.trim()
                if (title.isBlank()) return@mapNotNull null
                SManga(
                    url = "$baseUrl/g/$id/",
                    title = title,
                    thumbnailUrl = imageUrl(img)
                )
            }.distinctBy { it.url }
        return MangasPage(results, results.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("#info h1, .title h1, h1")?.text()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("#cover img, .cover img")?.let(::imageUrl)
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val id = galleryId(manga.url) ?: throw IOException("Invalid nhentai.xxx gallery address")
        val pages = galleryPageCount(doc, id)
        if (pages <= 0) throw IOException("nhentai.xxx did not return a page count for this gallery")
        return listOf(SChapter(manga.url, "Gallery ($pages pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val id = galleryId(chapter.url) ?: throw IOException("Invalid nhentai.xxx gallery address")
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val count = galleryPageCount(doc, id)
        if (count <= 0) throw IOException("nhentai.xxx did not return a page count for this gallery")

        val embedded = doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull(::imageUrl)
            .filter(::isGalleryImage)
            .distinct()
        if (embedded.size >= count) return embedded.take(count)

        // The site exposes each full-size page at /g/<id>/<page>/. Fetch those pages concurrently,
        // extracting the image URL rather than handing the reader an HTML page URL.
        val gate = Semaphore(6)
        return coroutineScope {
            (1..count).map { page ->
                async {
                    gate.withPermit { pageImage(id, page) }
                }
            }.awaitAll()
        }
    }

    private suspend fun pageImage(id: String, page: Int): String {
        val url = "$baseUrl/g/$id/$page/"
        val doc = Jsoup.parse(getString(url), url)
        return doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull(::imageUrl)
            .firstOrNull(::isGalleryImage)
            ?: throw IOException("nhentai.xxx did not return image $page")
    }

    private fun isGalleryImage(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith("nhentaimg.com") &&
            !url.substringBefore('?').endsWith("/thumb.jpg", ignoreCase = true)
    }

    private fun galleryPageCount(doc: Document, id: String): Int {
        val linkedPages = doc.select("a[href]")
            .filter { it.attr("href").startsWith("/g/$id/") }
            .mapNotNull { Regex("""/g/\Q$id\E/(\d+)/?""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        val fromText = Regex("""(?i)\b(\d{1,4})\s+pages?\b""").find(doc.text())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fromMetadata = doc.selectFirst("#load_pages, [name=load_pages], [data-page-count]")
            ?.let { it.attr("value").ifBlank { it.attr("data-page-count") }.toIntOrNull() } ?: 0
        return maxOf(linkedPages, fromText, fromMetadata)
    }

    private fun galleryId(url: String): String? =
        Regex("""/g/(\d+)(?:/|$)""").find(url)?.groupValues?.get(1)
}

/** 9hentai.so's live JSON API provides search results, gallery metadata, and page counts. */
class NineHentaiSource : HttpSource() {
    override val id = "9hentai_so"
    override val name = "9hentai.so"
    override val lang = "en"
    override val baseUrl = "https://9hentai.so"
    override val nsfw = true

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getPopular(page: Int): MangasPage = searchPage("", page)
    override suspend fun getLatest(page: Int): MangasPage = searchPage("", page)
    override suspend fun search(query: String, page: Int): MangasPage = searchPage(query, page)

    private suspend fun searchPage(query: String, page: Int): MangasPage {
        val form = JSONObject()
            .put("text", query)
            .put("page", (page - 1).coerceAtLeast(0))
            .put("sort", 0)
            .put("pages", JSONObject().put("range", JSONArray().put(0).put(2000)))
            .put(
                "tag",
                JSONObject()
                    .put("text", "")
                    .put("type", 1)
                    .put("tags", JSONArray())
                    .put("items", JSONObject().put("included", JSONArray()).put("excluded", JSONArray()))
            )
        val json = postJson("$baseUrl/api/getBook", JSONObject().put("search", form))
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { i ->
            val entry = results.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = entry.optString("title").ifBlank { return@mapNotNull null }
            val imageServer = entry.optString("image_server").trimEnd('/')
            SManga(
                url = "$baseUrl/g/$id/",
                title = title,
                thumbnailUrl = if (imageServer.isNotBlank()) "$imageServer/$id/cover-small.jpg" else "$baseUrl/images/$id/cover-small.jpg"
            )
        }
        return MangasPage(items.distinctBy { it.url }, items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val book = bookById(galleryId(manga.url) ?: throw IOException("Invalid 9hentai.so gallery address"))
        manga.title = book.optString("title").ifBlank { manga.title }
        val id = book.opt("id")?.toString().orEmpty()
        val server = book.optString("image_server").trimEnd('/')
        if (id.isNotBlank() && server.isNotBlank()) manga.thumbnailUrl = "$server/$id/cover-small.jpg"
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = galleryId(manga.url) ?: throw IOException("Invalid 9hentai.so gallery address")
        val book = bookById(id)
        val count = book.optInt("total_page", 0)
        if (count <= 0) throw IOException("9hentai.so returned an empty gallery")
        return listOf(SChapter(manga.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val id = galleryId(chapter.url) ?: throw IOException("Invalid 9hentai.so gallery address")
        val book = bookById(id)
        val count = book.optInt("total_page", 0)
        val server = book.optString("image_server").trimEnd('/')
        if (count <= 0 || server.isBlank()) throw IOException("9hentai.so returned incomplete gallery metadata")
        return (1..count).map { "$server/$id/$it.jpg" }
    }

    private suspend fun bookById(id: String): JSONObject {
        val json = postJson("$baseUrl/api/getBookByID", JSONObject().put("id", id))
        return json.optJSONObject("results") ?: throw IOException("9hentai.so could not find that gallery")
    }

    private suspend fun postJson(url: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            val text = response.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("9hentai.so returned invalid JSON") }
        }
    }

    private fun galleryId(url: String): String? =
        Regex("""/g/(\d+)(?:/|$)""").find(url)?.groupValues?.get(1)
}

/** Source for the current xoxocomic.com comic/issue site; the older .net source stays available. */
class XoxoComicSource : HttpSource() {
    override val id = "xoxocomic_com"
    override val name = "XOXO Comic"
    override val lang = "en"
    override val baseUrl = "https://xoxocomic.com"

    override suspend fun getPopular(page: Int): MangasPage =
        list(if (page <= 1) baseUrl else "$baseUrl/comic-list?page=$page")

    override suspend fun getLatest(page: Int): MangasPage = getPopular(page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val suffix = if (page > 1) "&page=$page" else ""
        return list("$baseUrl/search-comic?keyword=${enc(query)}$suffix")
    }

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select("a[href]")
            .filter {
                it.attr("href").startsWith("/comic/") &&
                    it.selectFirst("img") != null &&
                    !it.attr("href").contains("/issue-")
            }
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val title = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
                    .removeSuffix(" Comic").trim()
                if (title.isBlank() || !href.startsWith("$baseUrl/comic/")) return@mapNotNull null
                SManga(url = href, title = title, thumbnailUrl = imageUrl(a.selectFirst("img")))
            }.distinctBy { it.url }
        return MangasPage(items, items.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("main h1, h1")?.text()?.removeSuffix(" Comic")?.trim()
            ?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("main img[alt*=cover]")?.let(::imageUrl) ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val rows = doc.select("a[href]")
            .filter { it.attr("href").contains("/issue-") }
            .mapNotNull { a ->
                val url = a.absUrl("href").takeIf { it.startsWith("$baseUrl/comic/") } ?: return@mapNotNull null
                val name = a.text().trim().ifBlank { a.attr("title").trim() }.ifBlank { "Issue" }
                val number = Regex("""(?i)(?:issue|#)[\s-]*(\d+(?:\.\d+)?)""").find(name)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                SChapter(url, name, chapterNumber = number)
            }.distinctBy { it.url }
        if (rows.isEmpty()) throw IOException("XOXO Comic did not return issue links for this series")
        return rows.sortedWith(
            compareByDescending<SChapter> { it.chapterNumber >= 0f }
                .thenByDescending { it.chapterNumber }
        )
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        val doc = Jsoup.parse(html, chapter.url)
        val images = doc.select("#imgChapter img, .page-chapter img, .reading img, .reader img")
            .mapNotNull(::imageUrl)
            .filterNot { it.contains("logo", true) || it.contains("loading", true) || it.contains("avatar", true) }
            .distinct()
        if (images.isNotEmpty()) return images

        // Some XOXO reader templates put their page URLs in inline scripts instead of image tags.
        val embedded = Regex("""https?:\\?/\\?/[^\"'\s\\]+?\.(?:jpe?g|png|webp|avif)(?:\?[^\"'\s\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.replace("\\/", "/") }
            .filterNot { it.contains("logo", true) || it.contains("loading", true) || it.contains("avatar", true) }
            .distinct()
            .toList()
        if (embedded.isNotEmpty()) return embedded
        throw IOException("XOXO Comic returned the issue page without reader images; try opening the source in WebView to clear any site challenge")
    }
}

private fun imageUrl(img: Element?): String? {
    if (img == null) return null
    for (attribute in listOf("data-src", "data-lazy-src", "data-original", "src")) {
        val value = img.attr(attribute).trim()
        if (value.isNotBlank() && !value.startsWith("data:")) {
            return img.absUrl(attribute).ifBlank { value }
        }
    }
    val srcset = img.attr("srcset").substringBefore(',').substringBefore(' ').trim()
    return srcset.takeIf { it.isNotBlank() }?.let {
        runCatching { URI(img.baseUri()).resolve(it).toString() }.getOrDefault(it)
    }
}
