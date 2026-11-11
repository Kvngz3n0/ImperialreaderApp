package com.imperialreader.data.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class HentaiMamaSource : AnimeHttpSource() {
    override val id = "hentaimama_io"
    override val name = "HentaiMama"
    override val baseUrl = BASE
    override val nsfw = true

    override suspend fun getPopular(page: Int) = browse(page)
    override suspend fun getLatest(page: Int) = browse(page)

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank() || blocked(listOf(query))) return MangasPage(emptyList(), false)
        require(page > 0)
        val url = (if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/") + "?s=${enc(query.trim())}"
        return parseSearch(Jsoup.parse(getString(url), url))
    }

    private suspend fun browse(page: Int): MangasPage {
        require(page > 0)
        val url = if (page == 1) "$baseUrl/tvshows/" else "$baseUrl/tvshows/page/$page/"
        val parsed = parseListing(Jsoup.parse(getString(url), url))
        val limiter = Semaphore(4)
        val checked = coroutineScope {
            parsed.mangas.map { item ->
                async { limiter.withPermit { item.takeUnless { blocked(seriesTags(it.url) + it.title) } } }
            }.awaitAll().filterNotNull()
        }
        return MangasPage(checked, parsed.hasNextPage)
    }

    private suspend fun series(url: String): Document {
        if (!isSeriesUrl(url)) throw IOException("Invalid HentaiMama series URL")
        return Jsoup.parse(getString(url), url)
    }

    private suspend fun seriesTags(url: String): List<String> {
        val doc = series(url)
        return validateSeries(doc)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = series(manga.url)
        val tags = validateSeries(doc)
        if (blocked(tags + listOfNotNull(doc.selectFirst("h1.dsc-title")?.text()))) {
            throw IOException("Blocked: this title is tagged as depicting minors")
        }
        manga.genre = tags.joinToString(", ").ifBlank { null }
        manga.description = doc.selectFirst(".dsc-desc")?.text()?.ifBlank { null }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = series(manga.url)
        validateSeries(doc)
        val episodes = parseEpisodes(doc)
        if (episodes.isEmpty()) throw IOException("HentaiMama returned no available episodes")
        return episodes
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        if (!isEpisodeUrl(chapter.url)) throw IOException("Invalid HentaiMama episode URL")
        val page = Jsoup.parse(getString(chapter.url), chapter.url)
        val tags = parseEpisodeTags(page)
        if (tags.isEmpty()) throw IOException("HentaiMama episode tags not found")
        if (blocked(tags)) throw IOException("Blocked: this episode is tagged as depicting minors")
        val postId = parsePostId(page) ?: throw IOException("HentaiMama episode id not found")
        val mirrors = parseMirrors(page)
        if (mirrors.isEmpty()) throw IOException("HentaiMama did not list any mirrors")
        val referer = chapter.url
        return mirrors.map { (label, index) ->
            VideoOption(label, false) { resolveMirror(postId, index, referer) }
        }
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages {
        var last: Exception? = null
        for (option in getVideoOptions(episode)) {
            try {
                return option.resolve()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("HentaiMama did not provide a playable stream")
    }

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages {
        if (dub) throw IOException("HentaiMama does not provide a separate dub")
        return loadEpisode(chapter)
    }

    private suspend fun resolveMirror(postId: String, index: Int, referer: String): ChapterPages {
        val response = postString(
            "$baseUrl/wp-admin/admin-ajax.php",
            mapOf("action" to "get_player_contents", "a" to postId, "i" to index.toString()),
            mapOf("Referer" to referer)
        )
        val embed = parseEmbedUrl(response, index) ?: throw IOException("HentaiMama mirror is empty")
        val html = getString(embed, mapOf("Referer" to referer))
        val stream = parseHlsUrl(html) ?: throw IOException("HentaiMama mirror has no supported HLS stream")
        return ChapterPages(listOf(stream), mapOf("Referer" to "$baseUrl/"))
    }

    companion object {
        private const val BASE = "https://hentaimama.io"
        private val trustedStreamHosts = setOf("hentaidoge.org")
        private val extraBlockedWords = listOf("loli", "shota", "shouta", "toddlercon", "underage", "preteen")
        private val episodeNumber = Regex("""(?i)\b(?:episode|ep)\s*(\d+(?:\.\d+)?)""")

        private fun sameSite(url: String): URI? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            return uri.takeIf { it.scheme == "https" && it.host == "hentaimama.io" && it.userInfo == null }
        }

        private fun isPath(url: String, pattern: Regex): Boolean {
            val uri = sameSite(url) ?: return false
            return uri.rawQuery == null && uri.path.matches(pattern)
        }

        private fun isSeriesUrl(url: String) = isPath(url, Regex("""/tvshows/[^/]+/"""))
        private fun isEpisodeUrl(url: String) = isPath(url, Regex("""/episodes/[^/]+/"""))

        private fun blocked(values: List<String>): Boolean {
            val normalized = values.map { it.replace(Regex("""[^A-Za-z0-9_]+"""), " ") }
            return Rule34WorldSource.isBlockedTags(normalized) ||
                normalized.any { v -> v.lowercase().split(' ').any { t -> extraBlockedWords.any { t.startsWith(it) } } }
        }

        private fun genres(root: Element, selector: String): List<String> =
            root.select(selector).map { it.text().trim() }.filter { it.isNotBlank() }

        internal fun validateSeries(doc: Document): List<String> {
            val tags = genres(doc, ".dsc-genres a[rel=tag]")
            if (tags.isEmpty()) throw IOException("HentaiMama did not provide content tags")
            return tags
        }

        internal fun parseEpisodeTags(doc: Document) = genres(doc, ".ep-genres a[rel=tag]")

        internal fun parsePostId(doc: Document): String? {
            doc.selectFirst("#dt-dl-modal[data-post]")?.attr("data-post")?.takeIf { it.matches(Regex("""\d+""")) }
                ?.let { return it }
            return Regex(""""episode":"(\d+)"""").find(doc.html())?.groupValues?.get(1)
        }

        /** Mirror tab labels with the index sent to the player endpoint, e.g. "mi-2" -> 3. */
        internal fun parseMirrors(doc: Document): List<Pair<String, Int>> =
            doc.select(".dt-mi-tabs a[href^=#option-]").mapNotNull { a ->
                val index = a.attr("href").removePrefix("#option-").toIntOrNull() ?: return@mapNotNull null
                a.text().trim().ifBlank { "Mirror $index" } to index
            }.distinctBy { it.second }

        internal fun parseEmbedUrl(json: String, index: Int): String? {
            val html = runCatching { JSONArray(json).optString(index - 1) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return null
            val src = Jsoup.parseBodyFragment(html, "$BASE/").selectFirst("iframe[src]")?.absUrl("src") ?: return null
            return src.takeIf { isTrustedEmbed(it) }
        }

        private fun isTrustedEmbed(url: String): Boolean {
            val uri = sameSite(url) ?: return false
            return uri.rawQuery?.contains("dt_embed=hls") == true
        }

        internal fun parseHlsUrl(html: String): String? {
            val match = Regex(""""file"\s*:\s*"([^"]+?\.m3u8)"""").find(html.replace("\\/", "/")) ?: return null
            val url = match.groupValues[1]
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            return url.takeIf {
                uri.scheme == "https" && uri.userInfo == null && uri.host in trustedStreamHosts
            }
        }

        private fun number(title: String): Float =
            episodeNumber.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

        internal fun parseEpisodes(doc: Document): List<SChapter> =
            doc.select(".dt-se-list a.dt-se-item[href]").mapNotNull { a ->
                val url = a.absUrl("href")
                if (!isEpisodeUrl(url)) return@mapNotNull null
                val title = a.selectFirst(".dt-se-title")?.text()?.trim().orEmpty()
                val num = a.selectFirst(".dt-se-num")?.text().orEmpty().ifBlank { title }
                SChapter(url, title.ifBlank { num }, chapterNumber = number(num))
            }.distinctBy { it.url }.sortedByDescending { it.chapterNumber }

        private fun nextPage(doc: Document): Boolean =
            doc.selectFirst("a.dt-pg-next[href]")?.absUrl("href")?.let { sameSite(it) != null } == true

        internal fun parseListing(doc: Document): MangasPage {
            val cards = doc.select("article.item.tvshows")
            if (cards.isEmpty() && doc.selectFirst(".pagination-wraper, .search-no-results, .no-results") == null) {
                throw IOException("HentaiMama catalog markup not found")
            }
            val items = cards.mapNotNull { card ->
                val link = card.selectFirst(".data h3 a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                val title = link.text().trim()
                if (!isSeriesUrl(url) || title.isBlank()) return@mapNotNull null
                SManga(url, title, thumbnailUrl = cover(card.selectFirst(".poster img")))
            }.distinctBy { it.url }
            return MangasPage(items, nextPage(doc))
        }

        internal fun parseSearch(doc: Document): MangasPage {
            val cards = doc.select("article.series-card")
            if (cards.isEmpty() && doc.selectFirst(".search-no-results, .no-results") == null) {
                throw IOException("HentaiMama search markup not found")
            }
            val items = cards.mapNotNull { card ->
                val link = card.selectFirst(".sc-title a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                val title = link.text().trim()
                val tags = genres(card, ".sc-genres a[rel=tag]")
                if (!isSeriesUrl(url) || title.isBlank() || tags.isEmpty() || blocked(tags + title)) {
                    return@mapNotNull null
                }
                SManga(url, title, thumbnailUrl = cover(card.selectFirst(".sc-poster img")), genre = tags.joinToString(", "))
            }.distinctBy { it.url }
            return MangasPage(items, nextPage(doc))
        }

        private fun cover(img: Element?): String? =
            img?.absUrl("src")?.takeIf { it.startsWith("https://hentaimama.io/") }
    }
}
