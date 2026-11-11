package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class UniqueStreamSource : AnimeHttpSource() {
    override val id = "anime_uniquestream_net"
    override val name = "AnimeStream"
    override val baseUrl = "https://anime.uniquestream.net"

    override suspend fun getPopular(page: Int): MangasPage =
        parseCatalog(JSONArray(getString("$baseUrl/api/v1/videos/popular?page=$page&limit=$PAGE_SIZE&type=series")))

    override suspend fun getLatest(page: Int): MangasPage =
        parseCatalog(JSONArray(getString("$baseUrl/api/v1/videos/new?page=$page&limit=$PAGE_SIZE&type=series")))

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        val result = JSONObject(getString("$baseUrl/api/v1/search?page=$page&limit=$PAGE_SIZE&query=${enc(query.trim())}&t=series"))
        val series = result.optJSONArray("series") ?: throw IOException("AnimeStream did not return search results")
        val parsed = parseCatalog(series)
        val total = result.optJSONObject("totals")?.optInt("series", -1) ?: -1
        return MangasPage(parsed.mangas, if (total >= 0) page * PAGE_SIZE < total else parsed.hasNextPage)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val series = seriesData(manga.url)
        series.optString("title").takeIf(String::isNotBlank)?.let { manga.title = it }
        series.optString("description").takeIf(String::isNotBlank)?.let { manga.description = it }
        val images = series.optJSONArray("images")
        if (images != null) {
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                if (image.optString("type") == "poster_tall") {
                    image.optString("url").takeIf(String::isNotBlank)?.let { manga.thumbnailUrl = it }
                    break
                }
            }
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val seasons = seriesData(manga.url).optJSONArray("seasons")
            ?: throw IOException("AnimeStream did not return a season list")
        val chapters = LinkedHashMap<String, SChapter>()
        for (index in 0 until seasons.length()) {
            val season = seasons.optJSONObject(index) ?: continue
            val id = validId(season.optString("content_id")) ?: throw IOException("Invalid AnimeStream season ID")
            val seasonName = season.optString("title").trim()
            var page = 1
            while (true) {
                val url = "$baseUrl/api/v1/season/$id/episodes?page=$page&limit=$PAGE_SIZE&order_by=asc"
                val entries = JSONArray(getString(url))
                val before = chapters.size
                parseEpisodes(entries, seasonName).forEach { chapters.putIfAbsent(it.url, it) }
                if (entries.length() < PAGE_SIZE) break
                if (chapters.size == before) throw IOException("AnimeStream repeated an episode page")
                page++
            }
        }
        if (chapters.isEmpty()) throw IOException("AnimeStream returned no episodes for ${manga.title}")
        return chapters.values.toList().reversed()
    }

    private suspend fun seriesData(url: String): JSONObject {
        val uri = runCatching { URI(url) }.getOrNull()
        val id = uri?.path?.split('/')?.getOrNull(2)?.let(::validId)
        if (uri?.host != "anime.uniquestream.net" || id == null) throw IOException("Invalid AnimeStream series URL")
        return JSONObject(getString("$baseUrl/api/v1/series/$id"))
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> =
        listOf(VideoOption("Site player", false, useWebView = true) { loadEpisode(chapter) })

    override suspend fun loadEpisode(episode: SChapter): ChapterPages {
        val uri = runCatching { URI(episode.url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host != "anime.uniquestream.net" ||
            !Regex("""^/watch/[A-Za-z0-9]+/?$""").matches(uri.path.orEmpty())
        ) throw IOException("Invalid AnimeStream episode URL")
        return ChapterPages(listOf(episode.url), imageHeaders())
    }

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages =
        throw IOException("AnimeStream episodes play in the site player and cannot be downloaded by the app")

    companion object {
        private const val PAGE_SIZE = 20
        private const val BASE_URL = "https://anime.uniquestream.net"
        private fun validId(value: String): String? = value.takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }

        fun parseCatalog(data: JSONArray): MangasPage {
            val entries = (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("type") != "show") return@mapNotNull null
                val id = validId(item.optString("content_id")) ?: return@mapNotNull null
                val title = item.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val slug = title.replace(Regex("""[^A-Za-z0-9]+"""), "-").trim('-').ifBlank { "anime" }
                SManga("$BASE_URL/series/$id/$slug", title, thumbnailUrl = item.optString("image").ifBlank { null })
            }.distinctBy { it.url }
            return MangasPage(entries, data.length() >= PAGE_SIZE)
        }

        fun parseEpisodes(data: JSONArray, season: String): List<SChapter> =
            (0 until data.length()).mapNotNull { index ->
                val episode = data.optJSONObject(index) ?: return@mapNotNull null
                val id = validId(episode.optString("content_id")) ?: return@mapNotNull null
                val title = episode.optString("title").trim()
                val number = episode.optString("episode").trim()
                val name = listOf(season, number.takeIf(String::isNotBlank)?.let { "Episode $it" }, title)
                    .filterNotNull().filter(String::isNotBlank).joinToString(" — ")
                SChapter("$BASE_URL/watch/$id", name.ifBlank { "Episode" })
            }.distinctBy { it.url }
    }
}
