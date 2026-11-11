package com.imperialreader.data.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

class Rule34TubeSource : HttpSource(), CreatorSearchSource {
    override val id = "rule34_tube"
    override val name = "Rule34 Tube"
    override val lang = "en"
    override val baseUrl = "https://rule34.tube"
    override val nsfw = true
    override val supportsGeneralBrowse = true
    override suspend fun galleryMediaType(manga: SManga): String = "Video"

    override suspend fun getPopular(page: Int): MangasPage {
        val url = paged("$baseUrl/models/", page)
        val doc = Jsoup.parse(getString(url), url)
        val artists = doc.select("#list_models_models_list_items .thumb a[href]").mapNotNull { link ->
            val artistUrl = link.absUrl("href").takeIf { isArtistUrl(it) } ?: return@mapNotNull null
            val name = link.attr("title").trim().ifBlank { link.selectFirst(".title")?.text()?.trim().orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            SManga(artistUrl, name, thumbnailUrl = lazyImage(link.selectFirst("img")), artist = name)
        }.distinctBy { it.url }
        return MangasPage(artists, doc.selectFirst("#list_models_models_list a[href][data-action=ajax]") != null)
    }

    override suspend fun getLatest(page: Int): MangasPage =
        creatorListing(videos(paged("$baseUrl/latest-updates/", page), "#list_videos_latest_videos_list_items"))

    private suspend fun creatorListing(posts: MangasPage): MangasPage {
        val limiter = Semaphore(4)
        val artists = coroutineScope {
            posts.mangas.map { post ->
                async {
                    limiter.withPermit {
                        val doc = Jsoup.parse(getString(post.url), post.url)
                        if (isBlocked(doc)) emptyList()
                        else parseArtists(doc, post.thumbnailUrl)
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
        return MangasPage(artists, posts.hasNextPage)
    }

    override suspend fun getGeneral(page: Int): MangasPage =
        videos(paged("$baseUrl/latest-updates/", page), "#list_videos_latest_videos_list_items")

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        if (Rule34WorldSource.isBlockedTags(listOf(query))) return MangasPage(emptyList(), false)
        return videos("$baseUrl/search/?q=${enc(query.trim())}&from_videos=$page",
            "#list_videos_videos_list_search_result_items")
    }

    override suspend fun searchCreators(query: String, page: Int): MangasPage =
        creatorListing(search(query, page))

    private suspend fun videos(url: String, container: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val root = doc.selectFirst(container) ?: throw IOException("Rule34 Tube did not return a video listing")
        val entries = parseVideos(root)
        val hasNext = doc.select(".pagination a.next").any { it.attr("href") != "#" || it.hasAttr("data-parameters") }
        return MangasPage(entries, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        if (isArtistUrl(manga.url)) {
            manga.artist = manga.title
            return manga
        }
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        ensureAllowed(doc)
        val tags = videoTags(doc)
        if (tags.isNotEmpty()) manga.genre = tags.joinToString(", ")
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        if (!isArtistUrl(manga.url)) {
            ensureAllowed(Jsoup.parse(getString(manga.url), manga.url))
            return listOf(SChapter(manga.url, "Video", chapterNumber = 1f))
        }
        val chapters = LinkedHashMap<String, SChapter>()
        var url: String? = manga.url
        val visited = mutableSetOf<String>()
        while (url != null && visited.add(url)) {
            val doc = Jsoup.parse(getString(url), url)
            val root = doc.selectFirst("#list_videos_common_videos_list_items")
                ?: throw IOException("Rule34 Tube did not return this artist's video list")
            parseVideos(root).forEach { video ->
                chapters.putIfAbsent(video.url, SChapter(video.url, video.title))
            }
            val next = doc.selectFirst(".pagination a.next[href]")?.absUrl("href")
            url = next?.takeIf { isArtistPage(it, manga.url) }
        }
        if (chapters.isEmpty()) throw IOException("Rule34 Tube returned no videos for ${manga.title}")
        return chapters.values.toList()
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        ensureAllowed(Jsoup.parse(html, chapter.url))
        return listOf(resolveVideo(html))
    }

    private fun paged(url: String, page: Int): String = if (page <= 1) url else "$url$page/"

    companion object {
        private fun isArtistUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.host.equals("rule34.tube", true) &&
                Regex("""^/models/[^/]+/?$""").matches(uri.path.orEmpty())
        }

        private fun isArtistPage(url: String, artistUrl: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val artist = runCatching { URI(artistUrl) }.getOrNull() ?: return false
            return uri.host.equals(artist.host, true) &&
                uri.path.orEmpty().startsWith(artist.path.trimEnd('/') + "/") &&
                Regex("""\d+/?$""").containsMatchIn(uri.path.orEmpty())
        }

        fun parseArtists(doc: Document, cover: String?): List<SManga> =
            doc.select(".block.flex a.btn[href*=/models/]").mapNotNull { link ->
                val url = link.absUrl("href").takeIf(::isArtistUrl) ?: return@mapNotNull null
                val name = link.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                SManga(url, name, thumbnailUrl = cover, artist = name)
            }.distinctBy { it.url }

        fun parseVideos(root: org.jsoup.nodes.Element): List<SManga> =
            root.select(".thumb a[href*=/video/]").mapNotNull { link ->
                val url = link.absUrl("href")
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                if (!uri.host.equals("rule34.tube", true) || !uri.path.orEmpty().startsWith("/video/")) return@mapNotNull null
                val title = link.attr("title").trim().ifBlank { link.selectFirst(".title")?.text()?.trim().orEmpty() }
                if (title.isBlank()) return@mapNotNull null
                SManga(url, title, thumbnailUrl = lazyImage(link.selectFirst("img")))
            }.distinctBy { it.url }

        private fun videoTags(doc: Document): List<String> =
            doc.select("#video-tags a[href*=/tags/], .block.flex a[href*=/categories/]").map { it.text().trim() }
                .filter(String::isNotBlank).distinct()

        private fun isBlocked(doc: Document): Boolean = Rule34WorldSource.isBlockedTags(videoTags(doc))

        private fun ensureAllowed(doc: Document) {
            if (isBlocked(doc)) throw IOException("Blocked: this video is tagged as depicting minors")
        }

        fun resolveVideo(html: String): String {
            val player = Regex("""var\s+flashvars\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: throw IOException("Rule34 Tube player data is missing")
            fun value(key: String): String? =
                Regex("""\b${Regex.escape(key)}\s*:\s*['"]([^'"]+)['"]""")
                    .find(player)?.groupValues?.get(1)
            val encoded = value("video_url") ?: throw IOException("Rule34 Tube did not provide a video URL")
            if (!encoded.startsWith("function/0/")) {
                val uri = runCatching { URI(encoded) }.getOrNull()
                if (uri?.scheme != "https" || !uri.host.equals("rule34.tube", true)) {
                    throw IOException("Rule34 Tube provided an invalid video URL")
                }
                return encoded
            }
            val license = value("license_code")?.replace("$", "")
                ?.takeIf { it.length in 14..16 && it.all { c -> c in '0'..'9' } }
                ?: throw IOException("Rule34 Tube provided invalid player data")
            val nonzero = license.replace('0', '1')
            val middle = nonzero.length / 2
            val first = nonzero.substring(0, middle + 1).toLongOrNull()
                ?: throw IOException("Rule34 Tube provided invalid player data")
            val second = nonzero.substring(middle).toLongOrNull()
                ?: throw IOException("Rule34 Tube provided invalid player data")
            val sequence = (4 * kotlin.math.abs(first - second)).toString().take(middle + 1)
            val token = sequence.flatMapIndexed { i, digit ->
                (0..3).map { offset -> (license[i + offset].digitToInt() + digit.digitToInt()) % 10 }
            }
            val uri = runCatching { URI(encoded.removePrefix("function/0/")) }.getOrNull()
                ?: throw IOException("Rule34 Tube provided an invalid video URL")
            if (uri.scheme != "https" || !uri.host.equals("rule34.tube", true) || token.size < 32) {
                throw IOException("Rule34 Tube provided invalid player data")
            }
            val path = uri.rawPath.split('/').toMutableList()
            if (path.size < 4 || path[3].length < 32) throw IOException("Rule34 Tube provided an invalid video URL")
            val order = IntArray(32) { it }
            var sum = 0
            for (i in 31 downTo 0) {
                sum += token[i]
                val j = (i + sum) % 32
                val temp = order[i]
                order[i] = order[j]
                order[j] = temp
            }
            val hash = path[3]
            path[3] = order.joinToString("") { hash[it].toString() } + hash.substring(32)
            return "${uri.scheme}://${uri.rawAuthority}${path.joinToString("/")}" +
                (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        }
    }
}
