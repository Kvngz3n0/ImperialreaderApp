package com.imperialreader.data.source

import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll

/**
 * rule34.world — adult image/video archive (18+, hidden unless adult extensions are enabled).
 *
 * Popular/latest entries group posts by explicit artist, creator, user, or poster metadata. The General tab lists
 * individual posts directly; images open in the normal reader and videos (.mp4/.webm) use its built-in video page.
 *
 * The site is a JavaScript app, but its list pages and post pages are pre-rendered: lists contain /post/<id> links,
 * a post page holds the tag list, the preview image and the media URL on the storage CDN. Thumbnails follow the
 * CDN pattern posts/<id/1000>/<id>/<id>.picpreview.jpg.
 *
 * Posts tagged as depicting minors are never opened (see [isBlockedTags]) and searching for those tags returns nothing.
 */
class Rule34WorldSource : HttpSource(), CreatorSearchSource {
    override val id = "rule34_world"
    override val name = "Rule34 World"
    override val lang = "all"
    override val baseUrl = "https://rule34.world"
    override val nsfw = true
    override val supportsGeneralBrowse = true
    override suspend fun galleryMediaType(manga: SManga): String? {
        val media = post(manga.url)
        if (media.media == null) return null
        return if (media.isVideo) "Video" else "Photo"
    }

    // page-1 first post per list, to notice a list URL that ignores ?page= and would repeat itself forever
    private val firstPostByList = ConcurrentHashMap<String, String>()
    private val postCache = object : LinkedHashMap<String, Post>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Post>?): Boolean = size > 40
    }

    data class Creator(val name: String, val url: String)

    private suspend fun creatorListing(posts: MangasPage): MangasPage = coroutineScope {
        val limiter = Semaphore(4)
        val creatorPosts = posts.mangas.map { item ->
            async {
                limiter.withPermit {
                    val parsed = parsePost(getString(item.url), item.url)
                    if (isBlockedTags(parsed.tags)) emptyList()
                    else parsed.creators.map { it to item }
                }
            }
        }.awaitAll().flatten()
        val entries = LinkedHashMap<String, SManga>()
        creatorPosts.forEach { (creator, post) ->
            val existing = entries[creator.url]
            if (existing == null) {
                entries[creator.url] = SManga(
                    url = creator.url,
                    title = creator.name,
                    thumbnailUrl = post.thumbnailUrl,
                    artist = creator.name
                )
            } else if (existing.thumbnailUrl == null) {
                existing.thumbnailUrl = post.thumbnailUrl
            }
        }
        MangasPage(entries.values.toList(), posts.hasNextPage)
    }

    override suspend fun getPopular(page: Int): MangasPage = creatorListing(listing("/hot", page))

    override suspend fun getLatest(page: Int): MangasPage = creatorListing(listing("/", page))

    override suspend fun getGeneral(page: Int): MangasPage = listing("/", page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val tag = tagPath(query) ?: return MangasPage(emptyList(), false)
        if (isBlockedTags(listOf(tag.replace('_', ' ')))) return MangasPage(emptyList(), false)
        return listing("/$tag", page)
    }

    override suspend fun searchCreators(query: String, page: Int): MangasPage =
        creatorListing(search(query, page))

    // Artists are ordinary tags on this site, so an author search is just a tag search (the default would
    // open every result to look for an author field, which these posts do not have).
    override suspend fun searchByAuthor(query: String, page: Int): MangasPage = search(query, page)

    private suspend fun listing(path: String, page: Int): MangasPage {
        val url = if (page <= 1) "$baseUrl$path" else "$baseUrl$path${if ('?' in path) '&' else '?'}page=$page"
        val html = try {
            getString(url)
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) return MangasPage(emptyList(), false)
            throw e
        }
        val items = parseListing(html, baseUrl)
        if (items.isEmpty()) return MangasPage(emptyList(), false)
        val first = items.first().url
        if (page <= 1) firstPostByList[path] = first
        else if (firstPostByList[path] == first) return MangasPage(emptyList(), false)
        return MangasPage(items, true)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        if (!postPath.containsMatchIn(manga.url)) {
            manga.artist = manga.title
            return manga
        }
        val post = post(manga.url)
        manga.title = post.title
        manga.thumbnailUrl = post.previewUrl ?: manga.thumbnailUrl
        manga.genre = post.tags.joinToString(", ").ifBlank { null }
        manga.description = buildString {
            post.published?.let { append("Published: ").append(it).append("\n\n") }
            if (post.tags.isNotEmpty()) append("Tags: ").append(post.tags.joinToString(", "))
        }.ifBlank { null }
        manga.status = 2
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        if (!postPath.containsMatchIn(manga.url)) {
            val path = runCatching { URI(manga.url).rawPath }.getOrNull()
                ?: throw IOException("Rule34 World: invalid creator URL")
            val chapters = LinkedHashMap<String, SChapter>()
            val limiter = Semaphore(4)
            var page = 1
            var hasNext: Boolean
            do {
                val posts = listing(path, page)
                val pageChapters = coroutineScope {
                    posts.mangas.map { item ->
                        async {
                            limiter.withPermit {
                                val parsed = parsePost(getString(item.url), item.url)
                                if (isBlockedTags(parsed.tags) || parsed.media == null) null
                                else SChapter(
                                    url = item.url,
                                    name = parsed.title,
                                    chapterNumber = (item.url.substringAfterLast('/').toIntOrNull() ?: 0).toFloat()
                                )
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                pageChapters.forEach { chapters.putIfAbsent(it.url, it) }
                hasNext = posts.hasNextPage
                page++
            } while (hasNext)
            if (chapters.isEmpty()) throw IOException("Rule34 World: no posts with safe media found for this creator")
            return chapters.values.toList()
        }
        val post = post(manga.url)
        return listOf(SChapter(url = manga.url, name = if (post.isVideo) "Video" else "Image", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val post = post(chapter.url)
        return listOf(post.media ?: throw IOException("Rule34 World: no media file found for post ${post.id}"))
    }

    private suspend fun post(url: String): Post {
        val key = url.substringBefore('?').substringBefore('#')
        synchronized(postCache) { postCache[key] }?.let { return it }
        val parsed = parsePost(getString(key), key)
        if (isBlockedTags(parsed.tags)) {
            throw IOException("Blocked: this post is tagged as depicting minors and will not be opened")
        }
        synchronized(postCache) { postCache[key] = parsed }
        return parsed
    }

    class Post(
        val id: String,
        val title: String,
        val tags: List<String>,
        val creators: List<Creator>,
        val media: String?,
        val isVideo: Boolean,
        val previewUrl: String?,
        val published: String?
    )

    companion object {
        private const val CDN = "https://rule34storage.b-cdn.net"
        private val postPath = Regex("""/post/(\d+)""")
        private val aspectRatioTag = Regex("""^\d{1,2}:\d{1,2}$""")
        private val creatorKinds = setOf("artist", "creator", "user", "poster")

        // whole-word matches: "child bearing hips" must not be blocked, "loli" must
        private val blockedWords = setOf(
            "loli", "lolicon", "shota", "shotacon", "toddlercon", "underage", "preteen", "infant", "cub"
        )
        private val blockedTags = setOf("child", "children", "kid", "kids", "toddler", "minor", "young child")

        fun isBlockedTags(tags: List<String>): Boolean = tags.any { raw ->
            val tag = raw.lowercase().replace('_', ' ').trim()
            tag in blockedTags || tag.split(' ').any { it in blockedWords }
        }

        /** "Black Souls 2" -> black_souls_2, the single-tag page path; null for blank input. */
        fun tagPath(query: String): String? {
            val tag = query.trim().lowercase().replace(Regex("""\s+"""), "_").trim('/')
            if (tag.isBlank() || tag.contains('/') || tag.contains('?')) return null
            return java.net.URLEncoder.encode(tag, "UTF-8").replace("%3A", ":").replace("%28", "(").replace("%29", ")")
        }

        fun previewUrl(id: String): String = "$CDN/posts/${id.toLong() / 1000}/$id/$id.picpreview.jpg"

        fun parseListing(html: String, baseUrl: String): List<SManga> {
            val doc = Jsoup.parse(html, baseUrl)
            val seen = LinkedHashSet<String>()
            for (a in doc.select("a[href*=/post/]")) {
                postPath.find(a.attr("href"))?.groupValues?.get(1)?.let(seen::add)
            }
            return seen.map { id ->
                SManga(url = "${baseUrl.trimEnd('/')}/post/$id", title = "Post #$id", thumbnailUrl = previewUrl(id))
            }
        }

        fun parsePost(html: String, url: String): Post {
            val id = postPath.find(url)?.groupValues?.get(1) ?: throw IOException("Not a post URL: $url")
            val doc = Jsoup.parse(html, url)
            val tags = LinkedHashMap<String, String>()
            fun addTag(raw: String) {
                val tag = raw.trim().replace('_', ' ')
                if (tag.isNotBlank() && !aspectRatioTag.matches(tag) && !tag.startsWith("Show more", true)) {
                    tags.putIfAbsent(tag.lowercase(), tag)
                }
            }
            doc.select("h4 a[href]").forEach { a ->
                val path = runCatching { URI(a.absUrl("href")).path }.getOrNull().orEmpty().trim('/')
                if (path.isNotEmpty() && !path.contains('/')) addTag(a.text())
            }
            doc.selectFirst("meta[name=keywords]")?.attr("content")?.split(',')?.forEach(::addTag)
            val creators = LinkedHashMap<String, Creator>()
            fun addCreator(name: String, rawUrl: String?) {
                val cleanName = name.trim().replace('_', ' ').takeIf { it.isNotBlank() } ?: return
                val target = rawUrl?.takeIf { it.isNotBlank() }?.let {
                    runCatching { URI(url).resolve(it).toString() }.getOrNull()
                } ?: run {
                    val uri = URI(url)
                    "${uri.scheme}://${uri.authority}/${tagPath(cleanName)}"
                }
                if (!runCatching { URI(target).host.equals(URI(url).host, ignoreCase = true) }.getOrDefault(false)) return
                creators.putIfAbsent(target.trimEnd('/'), Creator(cleanName, target.trimEnd('/')))
            }
            val typedTag = Regex("""(?i)^(artist|creator|user|poster)\s*:\s*(.+)$""")
            doc.selectFirst("meta[name=keywords]")?.attr("content")?.split(',')?.forEach { raw ->
                typedTag.find(raw.trim())?.let { addCreator(it.groupValues[2], null) }
            }
            doc.select("a[href]").forEach { anchor ->
                val attributes = listOf(
                    anchor.attr("rel"), anchor.className(), anchor.attr("title"),
                    anchor.attr("data-type"), anchor.attr("data-tag-type")
                ).joinToString(" ")
                val pathKind = runCatching { URI(anchor.absUrl("href")).path.orEmpty().trim('/').substringBefore('/') }
                    .getOrDefault("")
                val normalizedPathKind = pathKind.removeSuffix("s")
                val kind = creatorKinds.firstOrNull { candidate ->
                    candidate == normalizedPathKind ||
                        Regex("""(?i)(?:^|[^\w])$candidate(?:$|[^\w])""").containsMatchIn(attributes)
                } ?: return@forEach
                val href = anchor.absUrl("href").takeIf { it.isNotBlank() }
                addCreator(anchor.text().ifBlank { anchor.attr("title") }.ifBlank { kind }, href)
            }
            doc.select("a[href]").forEach { anchor ->
                val linkText = anchor.text().trim()
                if (linkText.isBlank()) return@forEach
                val label = generateSequence(anchor.parent()) { it.parent() }
                    .take(3)
                    .mapNotNull { parent ->
                        val parentText = parent.text()
                        val index = parentText.indexOf(linkText)
                        if (index < 0) null
                        else Regex("""(?i)(artist|creator|user|poster)\s*:\s*$""")
                            .find(parentText.substring(0, index).trim())
                    }
                    .firstOrNull()
                if (label != null) addCreator(linkText, anchor.absUrl("href"))
            }

            val flat = html.replace("\\/", "/")
            val videoRegex = Regex("""https?://[^\s"'<>)\\]*?/posts/\d+/$id/[^\s"'<>)\\]*?\.(?:mp4|webm)""", RegexOption.IGNORE_CASE)
            val imageRegex = Regex("""https?://[^\s"'<>)\\]*?/posts/\d+/$id/$id\.pic\.[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
            val video = videoRegex.findAll(flat).map { it.value }.distinct().maxByOrNull { v ->
                Regex("""(\d{3,4})""").find(v.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0
            }
            val og = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            val image = imageRegex.find(flat)?.value ?: og?.replace("picpreview", "pic")
            val shown = tags.values.toList()
            return Post(
                id = id,
                title = shown.take(3).joinToString(", ").ifBlank { "Post #$id" },
                tags = shown,
                creators = creators.values.toList(),
                media = video ?: image,
                isVideo = video != null,
                previewUrl = og ?: previewUrl(id),
                published = doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }?.substringBefore('T')
            )
        }
    }
}
