package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

class WitchToonsSource : HttpSource() {
    override val id = "witchtoons_net"
    override val name = "DrakeComic"
    override val lang = "en"
    override val baseUrl = "https://drakecomic.net"

    override suspend fun getPopular(page: Int): MangasPage = list(page, "views")

    override suspend fun getLatest(page: Int): MangasPage = list(page, "latest")

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        var series: JSONArray? = null
        for (searchQuery in witchToonsSearchQueries(query)) {
            val json = JSONObject(
                getString("$baseUrl/api/search?q=${enc(searchQuery)}&contentMode=all")
            )
            series = json.optJSONArray("series")
                ?: throw IOException("DrakeComic returned no search results")
            if (series.length() > 0) break
        }
        val results = series ?: JSONArray()
        val mangas = (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.toManga()
        }
        return MangasPage(mangas, false)
    }

    private suspend fun list(page: Int, sort: String): MangasPage {
        val safePage = page.coerceAtLeast(1)
        val json = JSONObject(
            getString("$baseUrl/api/series?page=$safePage&limit=$PAGE_SIZE&sort=$sort")
        )
        val items = json.optJSONArray("data")
            ?: throw IOException("WitchToons returned no series catalog")
        val mangas = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.toManga()
        }
        return MangasPage(mangas, json.optJSONObject("meta")?.optBoolean("hasMore") == true)
    }

    private fun JSONObject.toManga(): SManga? {
        val slug = optString("urlSlug").ifBlank { optString("slug") }
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val cover = optString("coverImage").takeIf { it.isNotBlank() && it != "null" }
        return SManga(
            url = "$baseUrl/series/comic/$slug",
            title = title,
            thumbnailUrl = cover?.let(::absoluteUrl)
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val seriesPath = runCatching { URI(manga.url).path.trimEnd('/') }.getOrNull()
            ?.takeIf { it.startsWith("/series/comic/") && it.count { char -> char == '/' } == 3 }
            ?: throw IOException("WitchToons returned an invalid series address")
        val series = getSeriesPage(seriesPath, 1).series
        series.optString("title")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { manga.title = it }
        series.optString("coverImage")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { manga.thumbnailUrl = absoluteUrl(it) }
        series.optString("description").takeIf { it.isNotBlank() && it != "null" }
            ?.let { manga.description = it }
        manga.status = when (series.optString("status").uppercase()) {
            "ONGOING" -> 1
            "COMPLETED" -> 2
            "HIATUS" -> 3
            "CANCELLED", "CANCELED", "AXED", "DISCONTINUED" -> 4
            else -> manga.status
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val seriesPath = runCatching { URI(manga.url).path.trimEnd('/') }.getOrNull()
            ?.takeIf { it.startsWith("/series/comic/") && it.count { char -> char == '/' } == 3 }
            ?: throw IOException("WitchToons returned an invalid series address")
        val firstPage = getSeriesPage(seriesPath, 1)
        if (firstPage.totalPages > MAX_CHAPTER_PAGES) {
            throw IOException("WitchToons chapter list exceeds the safe limit of $MAX_CHAPTER_PAGES pages")
        }
        val chapters = LinkedHashMap<String, SChapter>()
        firstPage.chapters.forEach { chapter -> chapters[chapter.url] = chapter }
        for (page in 2..firstPage.totalPages) {
            getSeriesPage(seriesPath, page).chapters.forEach { chapter ->
                chapters[chapter.url] = chapter
            }
        }
        if (chapters.isEmpty()) throw IOException("WitchToons returned no chapters for this series")
        return chapters.values.sortedByDescending { it.chapterNumber }
    }

    private data class SeriesPage(
        val series: JSONObject,
        val chapters: List<SChapter>,
        val totalPages: Int
    )

    private suspend fun getSeriesPage(seriesPath: String, page: Int): SeriesPage {
        val query = if (page == 1) "?sort=desc" else "?sort=desc&page=$page"
        val response = getString("$baseUrl$seriesPath$query", RSC_HEADERS)
        val series = response.extractJsonObject("series")
            ?: throw IOException("WitchToons returned no series details")
        val chapterData = response.extractJsonArray("chapters")
            ?: throw IOException("WitchToons returned no chapter list")
        val totalPages = Regex(""""totalPages"\s*:\s*(\d+)""")
            .find(response.substring(chapterData.second))
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("WitchToons returned no chapter page count")
        val slug = seriesPath.substringAfterLast('/')
        val chapters = (0 until chapterData.first.length()).mapNotNull { index ->
            chapterData.first.optJSONObject(index)?.toChapter(slug)
        }
        return SeriesPage(series, chapters, totalPages.coerceAtLeast(1))
    }

    private fun JSONObject.toChapter(seriesSlug: String): SChapter? {
        if (!isWitchToonsChapterAccessible(this)) return null
        val number = optString("number").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val chapterNumber = number.toFloatOrNull() ?: parseChapterNumber(number)
        if (chapterNumber < 0f) return null
        val title = optString("title").takeIf { it.isNotBlank() && it != "null" && it != number }
        val name = title ?: "Chapter ${number.removeSuffix(".0")}"
        val dateUpload = try {
            optString("publishedAt").takeIf { it.isNotBlank() && it != "null" }
                ?.let { Instant.parse(it).toEpochMilli() } ?: 0L
        } catch (_: DateTimeParseException) {
            0L
        }
        return SChapter(
            url = "$baseUrl/series/comic/$seriesSlug/chapter/${number.removeSuffix(".0")}",
            name = name,
            dateUpload = dateUpload,
            chapterNumber = chapterNumber
        )
    }

    private fun String.extractJsonObject(key: String): JSONObject? {
        val json = extractJsonContainer(key, '{') ?: return null
        return runCatching { JSONObject(json.first) }.getOrNull()
    }

    private fun String.extractJsonArray(key: String): Pair<JSONArray, Int>? {
        val json = extractJsonContainer(key, '[') ?: return null
        val array = runCatching { JSONArray(json.first) }.getOrNull() ?: return null
        return array to json.second
    }

    private fun String.extractJsonContainer(key: String, opening: Char): Pair<String, Int>? {
        val marker = """"$key""""
        var markerIndex = indexOf(marker)
        while (markerIndex >= 0) {
            var start = markerIndex + marker.length
            while (start < length && this[start].isWhitespace()) start++
            if (start < length && this[start] == ':') {
                start++
                while (start < length && this[start].isWhitespace()) start++
                if (start < length && this[start] == opening) {
                    val closing = if (opening == '{') '}' else ']'
                    var depth = 0
                    var inString = false
                    var escaped = false
                    for (index in start until length) {
                        val char = this[index]
                        if (inString) {
                            when {
                                escaped -> escaped = false
                                char == '\\' -> escaped = true
                                char == '"' -> inString = false
                            }
                        } else {
                            when {
                                char == '"' -> inString = true
                                char == '{' || char == '[' -> depth++
                                char == '}' || char == ']' -> {
                                    depth--
                                    if (depth == 0 && char == closing) {
                                        return substring(start, index + 1) to index + 1
                                    }
                                }
                            }
                        }
                    }
                    return null
                }
            }
            markerIndex = indexOf(marker, markerIndex + marker.length)
        }
        return null
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val chapterUrl = canonicalWitchToonsChapterUrl(chapter.url, baseUrl)
        val html = getString(chapterUrl)
        val serializedImageUrls = parseWitchToonsPageUrls(html, baseUrl)
        if (serializedImageUrls.isEmpty()) {
            throw IOException("DrakeComic returned no readable pages for this chapter")
        }
        return serializedImageUrls
    }

    private companion object {
        val RSC_HEADERS = mapOf("rsc" to "1")
        const val PAGE_SIZE = 100
        const val MAX_CHAPTER_PAGES = 1000
    }
}

internal fun isWitchToonsChapterAccessible(chapter: JSONObject): Boolean =
    !chapter.optBoolean("isLocked") || chapter.optBoolean("hasAccess")

internal fun witchToonsSearchQueries(query: String): List<String> =
    listOf(query.trim(), query.trim().trimEnd { !it.isLetterOrDigit() })
        .filter(String::isNotBlank)
        .distinct()

internal fun canonicalWitchToonsChapterUrl(url: String, baseUrl: String): String {
    val uri = runCatching { URI(url) }.getOrElse {
        throw IOException("DrakeComic returned an invalid chapter address")
    }
    val host = uri.host?.lowercase()
    val allowedHost = host == "drakecomic.net" || host == "www.drakecomic.net" ||
        host == "witchtoons.net" || host?.endsWith(".witchtoons.net") == true
    val path = uri.rawPath.orEmpty()
    if (uri.scheme !in setOf("http", "https") || !allowedHost ||
        !path.matches(Regex("""/series/comic/[^/]+/chapter/[^/]+/?"""))) {
        throw IOException("DrakeComic returned an invalid chapter address")
    }
    return baseUrl.trimEnd('/') + path + (uri.rawQuery?.let { "?$it" } ?: "")
}

internal fun parseWitchToonsPageUrls(html: String, baseUrl: String): List<String> {
    val imageUrlPattern = Regex("""imageUrl\\?":\\?"((?:[^"\\]|\\u[0-9a-fA-F]{4})*)""")
    val escapedUnicode = Regex("""\\u([0-9a-fA-F]{4})""")
    return imageUrlPattern.findAll(html).mapNotNull { match ->
        val rawUrl = escapedUnicode.replace(match.groupValues[1]) { escaped ->
            escaped.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
        val uri = runCatching { URI(baseUrl).resolve(rawUrl) }.getOrNull() ?: return@mapNotNull null
        val path = uri.path.orEmpty()
        val isLegacyPage = path.contains("/uploads/comic-pages/")
        val isDrakePage = path.matches(
            Regex("""/uploads/series/[^/]+/[^/]+/.+\.(?:avif|gif|jpe?g|png|webp)""", RegexOption.IGNORE_CASE)
        )
        uri.takeIf { isLegacyPage || isDrakePage }?.toString()
    }.distinct().toList()
}
