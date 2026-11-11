package com.imperialreader.data.source

import org.json.JSONObject
import java.io.IOException
import java.net.URI

class AtsumaruSource : HttpSource() {
    override val id = "atsumaru_atsu_moe"
    override val name = "Atsumaru"
    override val lang = "en"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int): MangasPage = catalog("*", page, "trending:desc")
    override suspend fun getLatest(page: Int): MangasPage = catalog("*", page, "dateAdded:desc")
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false) else catalog(query.trim(), page, null)

    private suspend fun catalog(query: String, page: Int, sort: String?): MangasPage {
        require(page > 0)
        val filter = "isAdult:=false && hidden:!=true && medium:=Comic"
        val order = sort?.let { "&sort_by=${enc(it)}" }.orEmpty()
        val url = "$BASE/collections/manga/documents/search?q=${enc(query)}" +
            "&query_by=title,otherNames&filter_by=${enc(filter)}&page=$page&per_page=$PAGE_SIZE$order"
        return parseCatalog(JSONObject(getString(url)), page)
    }

    private suspend fun info(manga: SManga): JSONObject {
        val id = seriesId(manga.url)
        val data = JSONObject(getString("$BASE/api/manga/info?mangaId=${enc(id)}"))
        if (data.optBoolean("isAdult", true) || data.optString("medium") != "Comic") {
            throw IOException("Atsumaru title is unavailable in this source")
        }
        return data
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val data = info(manga)
        data.optString("title").takeIf(String::isNotBlank)?.let { manga.title = it }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = seriesId(manga.url)
        info(manga)
        val result = parseChapters(JSONObject(getString("$BASE/api/manga/allChapters?mangaId=${enc(id)}")), id)
        if (result.isEmpty()) throw IOException("Atsumaru returned no chapters for ${manga.title}")
        return result
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val uri = URI(chapter.url)
        if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 || uri.path != "/api/read/chapter") {
            throw IOException("Invalid Atsumaru chapter URL")
        }
        val pages = parsePages(JSONObject(getString(chapter.url)))
        if (pages.isEmpty()) throw IOException("Atsumaru returned no pages for ${chapter.name}")
        return pages
    }

    companion object {
        private const val HOST = "atsu.moe"
        private const val BASE = "https://$HOST"
        private const val CDN = "https://cdn.atsu.moe"
        private const val PAGE_SIZE = 24
        private val SERIES = Regex("""^/manga/([A-Za-z0-9_-]+)/?$""")

        private fun seriesId(url: String): String {
            val uri = URI(url)
            val match = SERIES.matchEntire(uri.path.orEmpty())
            if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 || match == null) {
                throw IOException("Invalid Atsumaru series URL")
            }
            return match.groupValues[1]
        }

        fun parseCatalog(data: JSONObject, page: Int): MangasPage {
            val hits = data.optJSONArray("hits") ?: throw IOException("Atsumaru returned an invalid catalog")
            val entries = (0 until hits.length()).mapNotNull { index ->
                val item = hits.optJSONObject(index)?.optJSONObject("document") ?: return@mapNotNull null
                val id = item.optString("id")
                val title = item.optString("title").trim()
                if (!id.matches(Regex("""[A-Za-z0-9_-]+""")) || title.isBlank() ||
                    item.optBoolean("isAdult", true) || item.optBoolean("hidden", true) ||
                    item.optString("medium") != "Comic") return@mapNotNull null
                val poster = item.optString("poster")
                val cover = if (poster.startsWith("/static/")) "$BASE$poster" else null
                SManga("$BASE/manga/$id", title, thumbnailUrl = cover,
                    description = item.optString("synopsis").takeIf(String::isNotBlank))
            }.distinctBy { it.url }
            return MangasPage(entries, page.toLong() * PAGE_SIZE < data.optLong("found"))
        }

        fun parseChapters(data: JSONObject, mangaId: String): List<SChapter> {
            val chapters = data.optJSONArray("chapters") ?: throw IOException("Atsumaru returned an invalid chapter list")
            return (0 until chapters.length()).mapNotNull { index ->
                val item = chapters.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (!id.matches(Regex("""[A-Za-z0-9_-]+""")) || item.optInt("pageCount") <= 0) return@mapNotNull null
                val number = item.optString("number").toFloatOrNull() ?: -1f
                SChapter("$BASE/api/read/chapter?mangaId=$mangaId&chapterId=$id",
                    item.optString("title").trim().ifBlank { "Chapter ${item.optString("number")}" },
                    dateUpload = item.optLong("createdAt"), chapterNumber = number)
            }.distinctBy { it.url }
        }

        fun parsePages(data: JSONObject): List<String> {
            val pages = data.optJSONObject("readChapter")?.optJSONArray("pages")
                ?: throw IOException("Atsumaru returned an invalid reader response")
            return (0 until pages.length()).mapNotNull { index ->
                val item = pages.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("image")
                if (!path.startsWith("/static/pages/") || path.contains("..") || path.contains('?') || path.contains('#')) {
                    return@mapNotNull null
                }
                item.optInt("number", index) to "$CDN$path"
            }.sortedBy { it.first }.map { it.second }
        }
    }
}
