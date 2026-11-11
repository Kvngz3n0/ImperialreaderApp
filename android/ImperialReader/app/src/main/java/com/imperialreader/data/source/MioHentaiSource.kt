package com.imperialreader.data.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

class MioHentaiSource : AnimeHttpSource() {
    override val id = "miohentai_com"
    override val name = "MioHentai"
    override val lang = "en"
    override val baseUrl = "https://miohentai.com"
    override val nsfw = true
    override suspend fun getPopular(page: Int): MangasPage = catalog("", page, "most-popular")
    override suspend fun getLatest(page: Int): MangasPage = catalog("", page, "recent")
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank() || Rule34WorldSource.isBlockedTags(listOf(query))) MangasPage(emptyList(), false)
        else catalog(query.trim(), page, "relevance")

    private suspend fun catalog(query: String, page: Int, sort: String): MangasPage {
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=se-search&mykeyword=${enc(query)}" +
            "&sort=$sort&includes=&excludes=&studios=&s_tag_match=all&s_studio_match=any" +
            "&duration_min=&duration_max=&name_starts_with=&screen_size=1024x768&log_search=0&paged=$page"
        val posts = parseCatalog(getString(url), page)
        val limiter = Semaphore(4)
        val checked = coroutineScope {
            posts.mangas.map { post ->
                async {
                    limiter.withPermit {
                        val doc = Jsoup.parse(getString(post.url), post.url)
                        post.takeUnless { blocked(doc) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return MangasPage(checked, posts.hasNextPage)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        if (isStudio(manga.url)) {
            manga.artist = manga.title
            return manga
        }
        val doc = checkedPost(manga.url)
        manga.genre = tags(doc).joinToString(", ").ifBlank { null }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        if (!isStudio(manga.url)) {
            checkedPost(manga.url)
            return listOf(SChapter(manga.url, "Episode 1", chapterNumber = 1f))
        }
        val chapters = LinkedHashMap<String, SChapter>()
        val visited = mutableSetOf<String>()
        var url: String? = manga.url
        while (url != null && visited.add(url)) {
            val doc = Jsoup.parse(getString(url), url)
            parseArchive(doc).forEach { post ->
                chapters.putIfAbsent(post.url, SChapter(post.url, post.title))
            }
            url = nextArchivePage(doc, manga.url)
        }
        if (chapters.isEmpty()) throw IOException("MioHentai returned no videos for ${manga.title}")
        return chapters.values.toList()
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> =
        listOf(VideoOption("Default", false) { loadEpisode(chapter) })

    override suspend fun loadEpisode(episode: SChapter): ChapterPages =
        ChapterPages(listOf(parseVideoUrl(checkedPost(episode.url))), imageHeaders())

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages {
        if (dub) throw IOException("MioHentai does not provide a separate dub")
        return loadEpisode(chapter)
    }

    private suspend fun checkedPost(url: String): Document {
        val doc = Jsoup.parse(getString(url), url)
        if (blocked(doc)) throw IOException("Blocked: this video is tagged as depicting minors")
        return doc
    }

    companion object {
        fun parseVideoUrl(doc: Document): String {
            if (blocked(doc)) throw IOException("Blocked: this video is tagged as depicting minors")
            return doc.selectFirst("video[data-video-src]")?.absUrl("data-video-src")
            ?.takeIf { uriHost(it) == "cdn.miohentai.com" && it.startsWith("https://") }
            ?: throw IOException("MioHentai did not provide a video URL")
        }
        private fun uriHost(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
        private fun isStudio(url: String): Boolean =
            uriHost(url) == "miohentai.com" && Regex("^/studio/[^/]+/?$").matches(URI(url).path.orEmpty())

        fun parseCatalog(json: String, page: Int): MangasPage {
            val data = JSONObject(json)
            if (!data.optBoolean("success")) throw IOException("MioHentai search failed")
            val posts = data.optJSONArray("posts") ?: throw IOException("MioHentai did not return a video listing")
            val entries = (0 until posts.length()).mapNotNull { index ->
                val post = posts.getJSONObject(index)
                val url = post.optString("url")
                val title = post.optString("title").trim()
                if (uriHost(url) != "miohentai.com" || title.isBlank()) null
                else SManga(url, title, thumbnailUrl = post.optString("image").ifBlank { null })
            }.distinctBy { it.url }
            return MangasPage(entries, page < data.optInt("pages"))
        }

        fun parseStudios(doc: Document, cover: String?): List<SManga> =
            doc.select(".post-metadata a[href*=/studio/]").mapNotNull { link ->
                val url = link.absUrl("href")
                val name = link.text().trim()
                if (!isStudio(url) || name.isBlank()) null
                else SManga(url, name, thumbnailUrl = cover, artist = name)
            }.distinctBy { it.url }

        private fun tags(doc: Document): List<String> =
            doc.select(".post-metadata .entry-tags a[href*=/tag/]").map { it.text().trim() }.filter(String::isNotBlank)

        private fun blocked(doc: Document): Boolean = Rule34WorldSource.isBlockedTags(tags(doc))

        fun parseArchive(doc: Document): List<SManga> =
            doc.select("article.tag-card").mapNotNull { card ->
                val cardTags = card.classNames().filter { it.startsWith("tag-") }
                    .map { it.removePrefix("tag-").replace('-', ' ') }
                if (Rule34WorldSource.isBlockedTags(cardTags)) return@mapNotNull null
                val link = card.selectFirst("a.tag-card-link[href]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                val title = card.selectFirst(".tag-card-title")?.text()?.trim().orEmpty()
                if (uriHost(url) != "miohentai.com" || title.isBlank()) null
                else SManga(url, title, thumbnailUrl = card.selectFirst("img[data-src]")?.absUrl("data-src"))
            }.distinctBy { it.url }

        fun nextArchivePage(doc: Document, studioUrl: String): String? {
            val current = doc.selectFirst(".pagination-above a[aria-current=page]")?.attr("data-page")?.toIntOrNull()
                ?: return null
            val link = doc.selectFirst(".pagination-above a[data-page=${current + 1}]") ?: return null
            val next = link.absUrl("href")
            val uri = runCatching { URI(next) }.getOrNull() ?: return null
            val root = URI(studioUrl)
            return next.takeIf {
                uri.scheme == "https" && uri.host.equals(root.host, true) &&
                    uri.path == root.path.trimEnd('/') + "/page/${current + 1}/"
            }
        }
    }
}
