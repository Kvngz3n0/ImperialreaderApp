package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

class WitchToonsSource : HttpSource() {
    override val id = "witchtoons_net"
    override val name = "WitchToons"
    override val lang = "en"
    override val baseUrl = "https://witchtoons.net"

    override suspend fun getPopular(page: Int): MangasPage = list(page, "views")

    override suspend fun getLatest(page: Int): MangasPage = list(page, "latest")

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        val json = JSONObject(
            getString("$baseUrl/api/search?q=${enc(query)}&contentMode=all")
        )
        val series = json.optJSONArray("series")
            ?: throw IOException("WitchToons returned no search results")
        val mangas = (0 until series.length()).mapNotNull { index ->
            series.optJSONObject(index)?.toManga()
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
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" | ")
                ?.takeIf { it.isNotBlank() }
            ?: manga.title
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.takeIf { it.isNotBlank() } ?: manga.description
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let(::absoluteUrl) ?: manga.thumbnailUrl
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val seriesPath = runCatching { URI(manga.url).path.trimEnd('/') }.getOrNull()
            ?.takeIf { it.startsWith("/series/") }
            ?: throw IOException("WitchToons returned an invalid series address")
        val firstPage = chapterList("$baseUrl$seriesPath")
        val lastPage = firstPage.second
        if (lastPage > MAX_CHAPTER_PAGES) {
            throw IOException("WitchToons chapter list exceeds the safe limit of $MAX_CHAPTER_PAGES pages")
        }
        val chapters = LinkedHashMap<String, SChapter>()
        firstPage.first.forEach { chapters[it.url] = it }
        for (page in 2..lastPage) {
            chapterList("$baseUrl$seriesPath?page=$page").first.forEach { chapters[it.url] = it }
        }
        if (chapters.isEmpty()) throw IOException("WitchToons returned no chapters for this series")
        return chapters.values.sortedByDescending { it.chapterNumber }
    }

    private suspend fun chapterList(url: String): Pair<List<SChapter>, Int> {
        val doc = Jsoup.parse(getString(url), url)
        val seriesPath = runCatching { URI(url).path.trimEnd('/') }.getOrNull().orEmpty()
        val chapters = doc.select("a[href]").mapNotNull { link ->
            val href = link.absUrl("href")
            val path = runCatching { URI(href).path.trimEnd('/') }.getOrNull().orEmpty()
            if (!path.startsWith("$seriesPath/chapter/")) return@mapNotNull null
            val name = link.text().trim().ifBlank { link.attr("title").trim() }
            val chapterNumber = path.substringAfterLast('/').toFloatOrNull()
                ?: parseChapterNumber(name)
            if (name.isBlank() && chapterNumber < 0f) return@mapNotNull null
            SChapter(
                url = href,
                name = name.ifBlank { "Chapter ${chapterNumber.toInt()}" },
                chapterNumber = chapterNumber
            )
        }.distinctBy { it.url }

        val lastPage = doc.select("a[href]").mapNotNull { link ->
            val target = runCatching { URI(link.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
            if (target.path.trimEnd('/') != seriesPath) return@mapNotNull null
            Regex("""(?:^|&)page=(\d+)(?:&|$)""").find(target.rawQuery.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 1
        return chapters to lastPage
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        val serializedImageUrls = Regex(
            """imageUrl\\?":\\?"((?:[^"\\]|\\u[0-9a-fA-F]{4})*)"""
        ).findAll(html).map { it.groupValues[1] }
            .map { it.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }.replace("\\/", "/") }
            .filter { it.contains("/uploads/comic-pages/") }
            .map(::absoluteUrl)
            .toList()
            .distinct()
        if (serializedImageUrls.isEmpty()) {
            throw IOException("WitchToons returned no readable pages for this chapter")
        }
        return serializedImageUrls
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_CHAPTER_PAGES = 1000
    }
}
