package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

class StoneScapeSource : HttpSource() {
    override val id = "wp_stonescape_xyz"
    override val name = "StoneScape"
    override val lang = "en"
    override val baseUrl = "https://stonescape.xyz"

    override suspend fun getPopular(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val json = JSONObject(
            getString("$baseUrl/api/series/popular?period=week&contentType=manhwa&limit=$PAGE_SIZE")
        )
        return MangasPage(parseSeries(json.optJSONArray("data")), false)
    }

    override suspend fun getLatest(page: Int): MangasPage =
        seriesList("page=${page.coerceAtLeast(1)}&limit=$PAGE_SIZE&contentType=manhwa")

    override suspend fun search(query: String, page: Int): MangasPage =
        seriesList(
            "search=${enc(query)}&page=${page.coerceAtLeast(1)}&limit=$PAGE_SIZE&contentType=manhwa"
        )

    private suspend fun seriesList(query: String): MangasPage {
        val json = JSONObject(getString("$baseUrl/api/series?$query"))
        val pagination = json.optJSONObject("pagination")
        val currentPage = pagination?.optInt("page", 1) ?: 1
        val totalPages = pagination?.optInt("totalPages", 1) ?: 1
        return MangasPage(parseSeries(json.optJSONArray("data")), currentPage < totalPages)
    }

    private fun parseSeries(data: JSONArray?): List<SManga> {
        if (data == null) return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("seriesId").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            SManga(
                url = "$baseUrl/series/$id",
                title = title,
                thumbnailUrl = item.optString("coverUrl")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let(::absoluteUrl)
            )
        }.distinctBy { it.url }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val id = seriesId(manga.url)
        val json = JSONObject(getString("$baseUrl/api/series/$id"))
        manga.title = json.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: manga.title
        manga.thumbnailUrl = json.optString("coverUrl")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let(::absoluteUrl) ?: manga.thumbnailUrl
        manga.description = json.optString("description").takeIf { it.isNotBlank() && it != "null" }
        manga.author = json.optString("author").takeIf { it.isNotBlank() && it != "null" }
        manga.artist = json.optString("artist").takeIf { it.isNotBlank() && it != "null" }
        manga.genre = json.optJSONArray("genres")?.let { genres ->
            (0 until genres.length()).map { genres.optString(it) }
                .filter { it.isNotBlank() && it != "null" }
                .joinToString(", ")
                .ifBlank { null }
        }
        manga.status = statusFromText(json.optString("publicationStatus"))
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = seriesId(manga.url)
        val json = JSONObject(getString("$baseUrl/api/series/$id/chapters"))
        val data = json.optJSONArray("chapters") ?: throw IOException("StoneScape returned no chapter list")
        val chapters = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val chapterId = item.optString("chapterId").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val numberText = item.optString("chapterNumber").takeIf { it.isNotBlank() && it != "null" }
            val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" }
            val name = buildString {
                append("Chapter ")
                append(numberText ?: (index + 1).toString())
                if (title != null) append(" - ").append(title)
            }
            SChapter(
                url = "$baseUrl/api/chapters/$chapterId/pages",
                name = name,
                dateUpload = item.optString("publishedAt").toEpochMillis(),
                chapterNumber = numberText?.toFloatOrNull() ?: -1f
            )
        }
        if (chapters.isEmpty()) throw IOException("StoneScape returned no readable chapters")
        return chapters.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val json = JSONObject(getString(chapter.url))
        val data = json.optJSONArray("pages") ?: throw IOException("StoneScape returned no page list")
        val pages = (0 until data.length()).mapNotNull { index ->
            val url = data.optJSONObject(index)?.optString("url")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            absoluteUrl(url)
        }
        if (pages.isEmpty()) throw IOException("StoneScape returned no readable pages")
        return pages
    }

    private fun seriesId(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: throw IOException("StoneScape returned an invalid series address")

    private fun String.toEpochMillis(): Long =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    private companion object {
        const val PAGE_SIZE = 20
    }
}
