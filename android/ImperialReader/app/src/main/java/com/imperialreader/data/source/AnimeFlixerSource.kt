package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class AnimeFlixerSource : AnimeHttpSource() {
    override val id = "anime_animeflixer_com"
    override val name = "AnimeFlixer"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int): MangasPage =
        parseCatalog(JSONArray(getString("$API/most-viewed?period=week&limit=100")), page)

    override suspend fun getLatest(page: Int): MangasPage =
        parseCatalog(JSONArray(getString("$API/latest-uploads?limit=70")), page)

    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false)
        else parseCatalog(JSONArray(getString("$API/filter?keyword=${enc(query.trim())}")), page)

    private suspend fun series(url: String): JSONObject {
        val uri = URI(url)
        val match = SERIES.matchEntire(uri.path.orEmpty())
        if (uri.scheme != "https" || uri.host != HOST || match == null) {
            throw IOException("Invalid AnimeFlixer series URL")
        }
        return JSONObject(getString("$API/series/${match.groupValues[1]}"))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val data = series(manga.url)
        data.optString("title").takeIf(String::isNotBlank)?.let { manga.title = it }
        data.optString("description").takeIf(String::isNotBlank)?.let { manga.description = it }
        data.optString("thumbnail_url").takeIf(String::isNotBlank)?.let { manga.thumbnailUrl = it }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val data = series(manga.url)
        val id = data.optLong("id").takeIf { it > 0 } ?: throw IOException("Invalid AnimeFlixer series ID")
        val episodes = data.optJSONArray("episodes") ?: throw IOException("AnimeFlixer did not return episodes")
        return parseEpisodes(episodes, id).also {
            if (it.isEmpty()) throw IOException("AnimeFlixer returned no episodes for ${manga.title}")
        }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        val uri = URI(chapter.url)
        val match = WATCH.matchEntire(uri.path.orEmpty())
        val seriesId = uri.rawQuery?.removePrefix("series=")?.toLongOrNull()
        if (uri.scheme != "https" || uri.host != HOST || match == null || seriesId == null || seriesId <= 0) {
            throw IOException("Invalid AnimeFlixer episode URL")
        }
        val episodeId = match.groupValues[1].toLong()
        val episodes = series("$BASE/series/$seriesId").optJSONArray("episodes")
            ?: throw IOException("AnimeFlixer did not return episodes")
        val episode = (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
            .firstOrNull { it.optLong("id") == episodeId }
            ?: throw IOException("AnimeFlixer episode is no longer available")
        val options = parsePlayers(episode).map { (label, url) ->
            VideoOption(label, label.contains("dub", ignoreCase = true)) {
                val path = URI(url).path.orEmpty().lowercase()
                if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
                    ChapterPages(listOf(url), imageHeaders())
                } else {
                    try {
                        resolveEmbed(url, "$BASE/")
                    } catch (e: IOException) {
                        WebViewStream.load(url, "$BASE/")?.stream
                            ?: throw IOException("AnimeFlixer $label: could not find a playable stream", e)
                    }
                }
            }
        }
        if (options.isEmpty()) throw IOException("AnimeFlixer did not provide an episode player")
        return options
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages =
        getVideoOptions(episode).first().resolve()

    companion object {
        private const val HOST = "animeflixer.com"
        private const val BASE = "https://$HOST"
        private const val API = "https://api.animeflixer.com/api"
        private const val PAGE_SIZE = 25
        private val SERIES = Regex("""^/series/(\d+)/?$""")
        private val WATCH = Regex("""^/watch/(\d+)/?$""")

        fun parsePlayers(episode: JSONObject): List<Pair<String, String>> {
            val versions = episode.optJSONArray("video_versions") ?: JSONArray()
            val defaultUrl = episode.optString("video_url")
            fun validUrl(url: String): Boolean {
                val uri = runCatching { URI(url) }.getOrNull()
                return uri?.scheme == "https" && !uri.host.isNullOrBlank()
            }
            val available = (0 until versions.length()).mapNotNull { index ->
                val version = versions.optJSONObject(index) ?: return@mapNotNull null
                val url = version.optString("video_url")
                if (!version.optBoolean("is_working", true) || !validUrl(url)) return@mapNotNull null
                version.optString("label").trim().ifBlank { "Site player" } to url
            }.distinctBy { it.second }.sortedByDescending { it.second == defaultUrl }
            return if (available.none { it.second == defaultUrl } && validUrl(defaultUrl)) {
                listOf("Site player" to defaultUrl) + available
            } else available
        }

        fun parseCatalog(data: JSONArray, page: Int): MangasPage {
            require(page > 0)
            val entries = (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                if (!item.optBoolean("is_series")) return@mapNotNull null
                val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                val title = item.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                SManga("$BASE/series/$id", title,
                    thumbnailUrl = item.optString("thumbnail_url").takeIf(String::isNotBlank))
            }.distinctBy { it.url }
            val start = ((page.toLong() - 1) * PAGE_SIZE).coerceAtMost(entries.size.toLong()).toInt()
            val end = (start + PAGE_SIZE).coerceAtMost(entries.size)
            return MangasPage(entries.subList(start, end), end < entries.size)
        }

        fun parseEpisodes(data: JSONArray, seriesId: Long): List<SChapter> =
            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                if (!item.optBoolean("is_released", true)) return@mapNotNull null
                val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                val season = item.optInt("season_number", 0)
                val number = item.optString("episode_number").trim()
                val title = item.optString("title").trim()
                val name = listOf(
                    season.takeIf { it > 0 }?.let { "Season $it" },
                    number.takeIf(String::isNotBlank)?.let { "Episode $it" },
                    title.takeIf(String::isNotBlank)
                ).filterNotNull().joinToString(" - ")
                SChapter("$BASE/watch/$id?series=$seriesId", name.ifBlank { "Episode" },
                    chapterNumber = number.toFloatOrNull() ?: -1f)
            }.distinctBy { it.url }.reversed()
    }
}
