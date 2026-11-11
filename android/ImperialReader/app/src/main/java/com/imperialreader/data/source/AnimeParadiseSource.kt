package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class AnimeParadiseSource : AnimeHttpSource() {
    override val id = "anime_animeparadise_moe"
    override val name = "AnimeParadise"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int): MangasPage =
        catalog("$API/search?sort=POPULARITY&page=$page&limit=25&v=1")

    override suspend fun getLatest(page: Int): MangasPage =
        parseCatalog(JSONObject(getString("$API/ep/recently-added?limit=25&page=$page&v=1")), true)

    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false)
        else catalog("$API/search?q=${enc(query.trim())}&page=$page&limit=25&v=1")

    private suspend fun catalog(url: String): MangasPage = parseCatalog(JSONObject(getString(url)), false)

    private suspend fun details(url: String): JSONObject {
        val uri = URI(url)
        val match = SERIES.matchEntire(uri.path.orEmpty())
        if (uri.scheme != "https" || uri.host != HOST || match == null) {
            throw IOException("Invalid AnimeParadise series URL")
        }
        val result = JSONObject(getString("$API/anime/${match.groupValues[1]}"))
        return result.optJSONObject("data") ?: throw IOException("AnimeParadise did not return series details")
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val data = details(manga.url)
        data.optString("title").takeIf(String::isNotBlank)?.let { manga.title = it }
        data.optString("synopsys").takeIf(String::isNotBlank)?.let { manga.description = it }
        data.optJSONObject("posterImage")?.optString("large")?.takeIf(String::isNotBlank)
            ?.let { manga.thumbnailUrl = it }
        data.optJSONArray("genres")?.let { genres ->
            manga.genre = (0 until genres.length()).mapNotNull { genres.optString(it).takeIf(String::isNotBlank) }
                .joinToString(", ")
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = details(manga.url).optString("_id").takeIf { ID.matches(it) }
            ?: throw IOException("AnimeParadise returned an invalid series ID")
        val data = JSONObject(getString("$API/anime/$id/episode")).optJSONArray("data")
            ?: throw IOException("AnimeParadise did not return episodes")
        return parseEpisodes(data, id).also {
            if (it.isEmpty()) throw IOException("AnimeParadise returned no episodes for ${manga.title}")
        }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> =
        listOf(VideoOption("Site player", false, useWebView = true) { loadEpisode(chapter) })

    override suspend fun loadEpisode(episode: SChapter): ChapterPages {
        val uri = URI(episode.url)
        val origin = uri.rawQuery?.removePrefix("origin=")
        if (uri.scheme != "https" || uri.host != HOST ||
            !WATCH.matches(uri.path.orEmpty()) || origin == null || !ID.matches(origin)
        ) throw IOException("Invalid AnimeParadise episode URL")
        return ChapterPages(listOf(episode.url), imageHeaders())
    }

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages =
        throw IOException("AnimeParadise uses the site player; downloads are not supported")

    companion object {
        private const val HOST = "www.animeparadise.moe"
        private const val BASE = "https://$HOST"
        private const val API = "https://api.animeparadise.moe"
        private val SERIES = Regex("""^/anime/([A-Za-z0-9_-]+)/?$""")
        private val WATCH = Regex("""^/watch/[A-Za-z0-9-]+/?$""")
        private val ID = Regex("""[A-Za-z0-9_-]+""")

        fun parseCatalog(result: JSONObject, recent: Boolean): MangasPage {
            val data = result.optJSONArray("data") ?: throw IOException("AnimeParadise did not return a catalog")
            val entries = (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val anime = if (recent) item.optJSONObject("origin") ?: return@mapNotNull null else item
                val slug = anime.optString("link").takeIf { ID.matches(it) } ?: return@mapNotNull null
                val title = anime.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val image = anime.optJSONObject("posterImage")?.optString("large")?.takeIf(String::isNotBlank)
                SManga("$BASE/anime/$slug", title, thumbnailUrl = image)
            }.distinctBy { it.url }
            val pagination = result.optJSONObject("pagination")
            return MangasPage(entries, pagination?.optBoolean("hasNext", false) ?: false)
        }

        fun parseEpisodes(data: JSONArray, origin: String): List<SChapter> =
            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val uid = item.optString("uid").takeIf { it.matches(Regex("""[A-Za-z0-9-]+""")) }
                    ?: return@mapNotNull null
                val number = item.optString("number").trim()
                val title = item.optString("title").trim()
                val name = listOf(number.takeIf(String::isNotBlank)?.let { "Episode $it" }, title)
                    .filterNotNull().filter(String::isNotBlank).joinToString(" - ")
                SChapter("$BASE/watch/$uid?origin=$origin", name.ifBlank { "Episode" },
                    chapterNumber = number.toFloatOrNull() ?: -1f)
            }.distinctBy { it.url }.sortedByDescending { it.chapterNumber }
    }
}
