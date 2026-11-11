package com.imperialreader.data.book

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class CyrisiaNovelSource : BookSource {
    override val id = "novel_cyrisia_com"
    override val name = "Cyrisia"
    override val webUrl = BASE
    override val description = "Web novels"
    override val isWebNovel = true

    private val mutex = Mutex()
    private var cached: List<BookItem>? = null
    private var cachedAt = 0L

    override suspend fun popular(page: Int): List<BookItem> = find("", page)

    override suspend fun search(query: String, page: Int): List<BookItem> =
        if (query.isBlank()) emptyList() else find(query.trim(), page)

    private suspend fun find(query: String, page: Int): List<BookItem> {
        if (page < 1) return emptyList()
        val entries = mutex.withLock {
            val current = cached
            if (current != null && System.currentTimeMillis() - cachedAt < CACHE_MS) current
            else parseCatalog(fetchHtml("$BASE/api/bookshelf")).also {
                cached = it
                cachedAt = System.currentTimeMillis()
            }
        }
        return entries.asSequence().filter { it.title.contains(query, true) }
            .drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).toList()
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        requireSiteUrl(item.id, "/series/")
        return parseChapters(fetchHtml(item.id), item.id).also {
            if (it.isEmpty()) throw IOException("Cyrisia has no chapters for ${item.title}")
        }
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        requireSiteUrl(chapter.url, "/read/")
        val content = Jsoup.parse(fetchHtml(chapter.url), chapter.url).selectFirst("article#ch-text")
            ?: throw IOException("Cyrisia chapter text was not found")
        content.select("script, style, noscript, iframe, nav, form").remove()
        if (content.select("p").none { it.text().isNotBlank() }) throw IOException("Cyrisia chapter is empty")
        return content.html()
    }

    companion object {
        private const val BASE = "https://cyrisia.com"
        private const val PAGE_SIZE = 30
        private const val CACHE_MS = 5 * 60 * 1000L

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun requireSiteUrl(url: String, prefix: String) {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "cyrisia.com" || !uri.path.startsWith(prefix)) {
                throw IOException("Invalid Cyrisia URL")
            }
        }

        fun parseCatalog(json: String): List<BookItem> {
            val array = JSONArray(json)
            val entries = mutableListOf<Pair<Long, BookItem>>()
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                if (entry.optString("kind") != "webnovel" || entry.optBoolean("mature")) continue
                val title = entry.optString("name").trim()
                val file = entry.optJSONArray("epubs")?.optString(0).orEmpty()
                if (title.isEmpty() || file.isEmpty() || entry.optInt("count") < 1) continue
                val series = encode(title)
                val item = BookItem(
                    "novel_cyrisia_com", "Cyrisia", "$BASE/series/$series", title, null,
                    "$BASE/api/cover?series=$series&v=${encode(file)}", null
                )
                entries.add(entry.optLong("views") to item)
            }
            if (entries.isEmpty()) throw IOException("Cyrisia returned no web novels")
            return entries.sortedByDescending { it.first }.map { it.second }.distinctBy { it.id }
        }

        fun parseChapters(html: String, seriesUrl: String): List<NovelChapter> {
            requireSiteUrl(seriesUrl, "/series/")
            val doc = Jsoup.parse(html, seriesUrl)
            val data = doc.selectFirst("script#ch-data")?.data()
                ?: throw IOException("Cyrisia chapter list was not found")
            val json = JSONObject(data)
            val series = json.getString("series")
            val rows = json.getJSONArray("rows")
            val chapters = mutableListOf<NovelChapter>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                val file = row.optString(0)
                val title = row.optString(1)
                if (file.isBlank() || title.isBlank() || !file.endsWith(".txt", true)) continue
                chapters.add(NovelChapter(title, "$BASE/read/${encode(series)}/${encode(file)}"))
            }
            return chapters.distinctBy { it.url }
        }
    }
}
