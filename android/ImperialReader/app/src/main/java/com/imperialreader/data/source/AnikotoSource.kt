package com.imperialreader.data.source

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

internal data class AnikotoServer(val label: String, val dub: Boolean, val linkId: String)

class AnikotoSource : AnimeHttpSource() {
    override val id = "anime_anikototv_to"
    override val name = "Anikoto"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int): MangasPage = catalog("$BASE/most-viewed?page=$page")
    override suspend fun getLatest(page: Int): MangasPage = catalog("$BASE/latest-updated?page=$page")
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false)
        else catalog("$BASE/search?keyword=${enc(query.trim())}&page=$page")

    private suspend fun catalog(url: String): MangasPage = parseCatalog(Jsoup.parse(getString(url), url))

    private suspend fun details(url: String): Document {
        requireSeriesUrl(url)
        return Jsoup.parse(getString(url), url)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = details(manga.url)
        doc.selectFirst("#w-info h1")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        doc.selectFirst("#w-info .synopsis .content")?.text()?.trim()
            ?.takeIf(String::isNotBlank)?.let { manga.description = it }
        doc.selectFirst("#w-info .poster img[src]")?.absUrl("src")
            ?.takeIf(String::isNotBlank)?.let { manga.thumbnailUrl = it }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = details(manga.url)
        val id = doc.selectFirst("#watch-main[data-id]")?.attr("data-id")
            ?.takeIf { it.matches(Regex("""\d+""")) } ?: throw IOException("Anikoto did not provide a series ID")
        val data = JSONObject(getString("$BASE/ajax/episode/list/$id", mapOf(
            "X-Requested-With" to "XMLHttpRequest", "Referer" to manga.url
        )))
        if (data.optInt("status") != 200) throw IOException("Anikoto could not load episodes: ${data.optString("message")}")
        return parseEpisodes(data.optString("result"), manga.url).also {
            if (it.isEmpty()) throw IOException("Anikoto returned no episodes for ${manga.title}")
        }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        requireEpisodeUrl(chapter.url)
        val uri = URI(chapter.url)
        val slug = uri.path.trimEnd('/').substringAfterLast("/ep-")
        val seriesUrl = "$BASE${uri.path.substringBeforeLast("/ep-")}"
        val doc = details(seriesUrl)
        val id = doc.selectFirst("#watch-main[data-id]")?.attr("data-id")
            ?.takeIf { it.matches(Regex("""\d+""")) } ?: throw IOException("Anikoto did not provide a series ID")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to chapter.url)
        val episodes = ajaxResult(getString("$BASE/ajax/episode/list/$id", headers), "episodes")
        val episode = Jsoup.parse(episodes).select("a[data-slug][data-ids]")
            .firstOrNull { it.attr("data-slug") == slug }
            ?: throw IOException("Anikoto could not find ${chapter.name}")
        val ids = episode.attr("data-ids").takeIf(String::isNotBlank)
            ?: throw IOException("Anikoto did not provide servers for ${chapter.name}")
        val servers = parseServers(ajaxResult(
            getString("$BASE/ajax/server/list?servers=${enc(ids)}", headers), "servers"
        ))
        if (servers.isEmpty()) throw IOException("Anikoto returned no servers for ${chapter.name}")
        return servers.map { server ->
            VideoOption(server.label, server.dub) {
                val data = JSONObject(getString("$BASE/ajax/server?get=${enc(server.linkId)}", headers))
                if (data.optInt("status") != 200) {
                    throw IOException("Anikoto could not load ${server.label}: ${data.optString("message")}")
                }
                val embed = data.optJSONObject("result")?.optString("url").orEmpty()
                val embedUri = runCatching { URI(embed) }.getOrNull()
                if (embedUri?.scheme != "https" || embedUri.host.isNullOrBlank()) {
                    throw IOException("Anikoto returned an invalid player URL for ${server.label}")
                }
                if (embedUri.path.orEmpty().lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mp4") }) {
                    return@VideoOption ChapterPages(listOf(embed), imageHeaders())
                }
                try {
                    resolveEmbed(embed, chapter.url)
                } catch (e: IOException) {
                    WebViewStream.load(embed, chapter.url)?.stream
                        ?: throw IOException("${server.label}: could not find a playable stream", e)
                }
            }
        }
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages {
        return getVideoOptions(episode).first().resolve()
    }

    companion object {
        private const val BASE = "https://anikototv.to"
        private val SERIES_PATH = Regex("""^/watch/([A-Za-z0-9_-]+)/?$""")
        private val EPISODE_PATH = Regex("""^/watch/[A-Za-z0-9_-]+/ep-[A-Za-z0-9._-]+/?$""")

        private fun ajaxResult(body: String, kind: String): String {
            val data = JSONObject(body)
            if (data.optInt("status") != 200) {
                throw IOException("Anikoto could not load $kind: ${data.optString("message")}")
            }
            return data.optString("result")
        }

        internal fun parseServers(html: String): List<AnikotoServer> =
            Jsoup.parse(html).select(".servers .type[data-type] li[data-link-id]").mapNotNull { item ->
                val type = item.parent()?.parent()?.attr("data-type")
                if (type != "sub" && type != "dub") return@mapNotNull null
                val id = item.attr("data-link-id").trim()
                val label = item.text().trim()
                if (id.isBlank() || label.isBlank()) return@mapNotNull null
                AnikotoServer("${type.uppercase()} $label", type == "dub", id)
            }.distinctBy { it.dub to it.linkId }

        private fun validPath(url: String, path: Regex): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.host == "anikototv.to" && uri.port == -1 &&
                uri.rawQuery == null && uri.rawFragment == null && path.matches(uri.path.orEmpty())
        }

        private fun requireSeriesUrl(url: String) {
            if (!validPath(url, SERIES_PATH)) throw IOException("Invalid Anikoto series URL")
        }

        private fun requireEpisodeUrl(url: String) {
            if (!validPath(url, EPISODE_PATH)) throw IOException("Invalid Anikoto episode URL")
        }

        fun parseCatalog(doc: Document): MangasPage {
            val entries = doc.select(".item .info a.name.d-title[href]").mapNotNull { link ->
                val uri = runCatching { URI(link.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
                val path = uri.path.orEmpty().replace(Regex("""/ep-[A-Za-z0-9._-]+/?$"""), "")
                val url = "$BASE$path"
                val title = link.text().trim()
                if (uri.scheme != "https" || uri.host != "anikototv.to" || uri.port != -1 ||
                    !validPath(url, SERIES_PATH) || title.isBlank()) return@mapNotNull null
                val image = link.closest(".inner")?.selectFirst(".poster img[src]")?.absUrl("src")
                SManga(url, title, thumbnailUrl = image?.takeIf(String::isNotBlank))
            }.distinctBy { it.url }
            if (entries.isEmpty() && doc.selectFirst(".item .info a.name.d-title") != null) {
                throw IOException("Anikoto returned an unsupported catalog")
            }
            return MangasPage(entries, doc.selectFirst(".pagination a[rel=next][href]") != null)
        }

        fun parseEpisodes(html: String, seriesUrl: String): List<SChapter> {
            requireSeriesUrl(seriesUrl)
            return Jsoup.parse(html).select("a[data-id][data-slug][data-num]").mapNotNull { link ->
                val slug = link.attr("data-slug")
                val url = "${seriesUrl.trimEnd('/')}/ep-$slug"
                if (!validPath(url, EPISODE_PATH)) return@mapNotNull null
                val number = link.attr("data-num").trim()
                val title = link.selectFirst(".d-title")?.text()?.trim().orEmpty()
                val name = "Episode $number" + if (title.isNotBlank() && title != "Episode $number") " - $title" else ""
                SChapter(url, name, chapterNumber = number.toFloatOrNull() ?: -1f)
            }.distinctBy { it.url }.reversed()
        }
    }
}
