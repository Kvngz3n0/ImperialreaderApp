package com.imperialreader.data.source

import com.imperialreader.data.network.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

class XAnimePornSource : AnimeHttpSource() {
    override val id = "xanimeporn_com"
    override val name = "XAnimePorn"
    override val baseUrl = BASE
    override val nsfw = true

    override suspend fun getPopular(page: Int) = catalog(page, "filtre=views")
    override suspend fun getLatest(page: Int) = catalog(page, "filtre=date")
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank() || blocked(listOf(query))) MangasPage(emptyList(), false)
        else catalog(page, "s=${enc(query.trim())}")

    private suspend fun catalog(page: Int, query: String): MangasPage {
        require(page > 0)
        val url = "$baseUrl/page/$page/?$query"
        val parsed = parseCatalog(Jsoup.parse(getString(url), url), page)
        val limiter = Semaphore(4)
        val checked = coroutineScope {
            parsed.mangas.map { item ->
                async {
                    limiter.withPermit {
                        val doc = post(item.url)
                        item.takeUnless { blockedPost(doc) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return MangasPage(checked, parsed.hasNextPage)
    }

    private suspend fun post(url: String): Document {
        if (!isPostUrl(url)) throw IOException("Invalid XAnimePorn episode URL")
        return Jsoup.parse(getString(url), url)
    }

    private suspend fun checkedPost(url: String): Document {
        val doc = post(url)
        if (blockedPost(doc)) throw IOException("Blocked: this episode is tagged as depicting minors")
        return doc
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = checkedPost(manga.url)
        manga.description = doc.selectFirst(".su-tabs-pane[data-title=Description]")?.text()?.ifBlank { null }
        manga.genre = tags(doc).joinToString(", ").ifBlank { null }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = checkedPost(manga.url)
        val episodes = parseEpisodes(doc)
        val limiter = Semaphore(4)
        return coroutineScope {
            episodes.map { episode ->
                async {
                    limiter.withPermit {
                        episode.takeUnless { blockedPost(post(episode.url)) }
                    }
                }
            }.awaitAll().filterNotNull()
        }.ifEmpty { throw IOException("XAnimePorn returned no available episodes") }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        val links = parseDownloads(checkedPost(chapter.url))
        if (links.isEmpty()) throw IOException("XAnimePorn did not provide downloadable video links")
        return links.map { (label, url) ->
            VideoOption(label, false) { resolveVideo(url, chapter.url) }
        }
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages =
        getVideoOptions(episode).first().resolve()

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages {
        if (dub) throw IOException("XAnimePorn does not provide a separate dub")
        return loadEpisode(chapter)
    }

    private suspend fun resolveVideo(url: String, referer: String): ChapterPages {
        val request = Request.Builder().url(url).header("Referer", referer).build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw IOException("XAnimePorn video returned HTTP ${response.code}")
            val finalUrl = response.request.url.toString()
            val uri = URI(finalUrl)
            if (uri.scheme != "https" || uri.host != "download.xanimeporn.com" ||
                !uri.path.endsWith(".mp4", ignoreCase = true) ||
                response.header("Content-Type").orEmpty().substringBefore(';') != "video/mp4"
            ) throw IOException("XAnimePorn did not return a direct MP4")
            return ChapterPages(listOf(finalUrl), mapOf("Referer" to referer))
        }
    }

    companion object {
        private const val BASE = "https://xanimeporn.com"
        private val episodeNumber = Regex("""(?i)\bepisode\s+(\d+(?:\.\d+)?)""")

        private fun isPostUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.host == "xanimeporn.com" &&
                uri.path.matches(Regex("""/[^/]+/""")) && uri.query == null
        }

        private fun blocked(values: List<String>): Boolean =
            Rule34WorldSource.isBlockedTags(values.map { it.replace(Regex("""[^A-Za-z0-9_]+"""), " ") })

        private fun tags(doc: Document): List<String> = doc.select("#cat-tag a[rel=tag]").map { it.text().trim() }

        internal fun blockedPost(doc: Document): Boolean {
            val tags = tags(doc)
            if (tags.isEmpty()) throw IOException("XAnimePorn did not provide content tags")
            return blocked(tags + doc.select("h1").map { it.text() })
        }

        fun parseCatalog(doc: Document, page: Int): MangasPage {
            val cards = doc.select("ul.listing-videos > li")
            if (cards.isEmpty() && doc.selectFirst("ul.listing-videos") == null) {
                throw IOException("XAnimePorn catalog markup was not found")
            }
            val items = cards.mapNotNull { card ->
                val link = card.selectFirst("a[href][title]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                val title = link.attr("title").trim()
                if (!isPostUrl(url) || title.isBlank() || blocked(listOf(title))) return@mapNotNull null
                val image = card.selectFirst("img")
                val cover = image?.absUrl("data-lazy-src")?.ifBlank { image.absUrl("src") }
                SManga(url, title, thumbnailUrl = cover?.takeIf { it.startsWith("https://") })
            }.distinctBy { it.url }
            val nextPage = doc.select(".pagination a[href]").any { link ->
                val uri = runCatching { URI(link.absUrl("href")) }.getOrNull()
                uri?.scheme == "https" && uri.host == "xanimeporn.com" && uri.path == "/page/${page + 1}/"
            }
            return MangasPage(items, nextPage)
        }

        fun parseEpisodes(doc: Document): List<SChapter> {
            val links = doc.select(".su-tabs-pane[data-title=Episodes] a[href]").mapNotNull { link ->
                val url = link.absUrl("href")
                val label = link.text().trim()
                if (!isPostUrl(url) || label.isBlank() || blocked(listOf(label))) null
                else SChapter(url, label, chapterNumber = episodeNumber.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f)
            }.distinctBy { it.url }
            return links.sortedByDescending { it.chapterNumber }
        }

        fun parseDownloads(doc: Document): List<Pair<String, String>> =
            doc.select(".su-tabs-pane[data-title=Downloads] a[href]").mapNotNull { link ->
                val url = link.absUrl("href")
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                val label = link.text().trim()
                if (uri.scheme != "https" || uri.host != "videos.xanimeporn.com" ||
                    uri.path != "/download.php" || label.isBlank()) null else label to url
            }.distinctBy { it.second }.sortedByDescending {
                Regex("""(\d+)p""", RegexOption.IGNORE_CASE).find(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
    }
}
