package com.imperialreader.data.source

import com.imperialreader.util.parseChapterNumber
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

class PawchiveSource : HttpSource() {
    private data class MediaFile(val path: String, val node: Int)

    override val id = "pawchive_pw"
    override val name = "Pawchive"
    override val lang = "en"
    override val baseUrl = "https://pawchive.pw"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage = posts("", page)
    override suspend fun getLatest(page: Int): MangasPage = posts("", page)
    override suspend fun search(query: String, page: Int): MangasPage = posts(query, page)

    private suspend fun posts(query: String, page: Int): MangasPage {
        val offset = ((page - 1).coerceAtLeast(0) * PAGE_SIZE)
        val queryPart = if (query.isBlank()) "" else "&q=${enc(query)}"
        val json = JSONArray(getString("$baseUrl/api/v1/posts?o=$offset$queryPart"))
        val entries = (0 until json.length()).mapNotNull { index ->
            val post = json.optJSONObject(index) ?: return@mapNotNull null
            val route = postRoute(post) ?: return@mapNotNull null
            val title = post.optString("title").trim()
            val cover = mediaFiles(post).firstOrNull()
            if (title.isBlank() || cover == null) return@mapNotNull null
            SManga(url = route, title = title, thumbnailUrl = mediaUrl(cover))
        }
        return MangasPage(entries, entries.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val post = loadPost(manga.url)
        val images = mediaFiles(post)
        return manga.copy(
            title = post.optString("title").trim().ifBlank { manga.title },
            thumbnailUrl = images.firstOrNull()?.let(::mediaUrl) ?: manga.thumbnailUrl,
            description = Jsoup.parse(post.optString("content")).text().trim().ifBlank { null }
        )
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val post = loadPost(manga.url)
        val title = post.optString("title").trim().ifBlank { manga.title }
        val route = postRoute(post) ?: throw IOException("Pawchive returned an invalid post")
        return listOf(SChapter(route, title, chapterNumber = parseChapterNumber(title)))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val post = loadPost(chapter.url)
        val pages = mediaFiles(post).map(::mediaUrl).distinct()
        if (pages.isEmpty()) throw IOException("Pawchive returned no readable images for this post")
        return pages
    }

    private suspend fun loadPost(route: String): JSONObject {
        val parts = parseRoute(route)
        val url = "$baseUrl/api/v1/${parts[0]}/user/${parts[1]}/post/${parts[2]}"
        val response = JSONObject(getString(url, mapOf("Accept" to "application/json")))
        return response.optJSONObject("post") ?: response
    }

    private fun postRoute(post: JSONObject): String? {
        val service = post.optString("service").takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: return null
        val user = post.optString("user").takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: return null
        val id = post.optString("id").takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: return null
        return "$baseUrl/posts/$service/$user/$id"
    }

    private fun parseRoute(raw: String): List<String> {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw IOException("Invalid Pawchive post address")
        if (!uri.host.equals(URI(baseUrl).host, ignoreCase = true)) {
            throw IOException("Invalid Pawchive post address")
        }
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 4 || parts[0] != "posts" ||
            parts.drop(1).any { !it.matches(Regex("[a-zA-Z0-9_-]+")) }
        ) throw IOException("Invalid Pawchive post address")
        return parts.drop(1)
    }

    private fun mediaFiles(post: JSONObject): List<MediaFile> = buildList {
        addImageFile(post.optJSONObject("file"))?.let { add(it) }
        val attachments = post.optJSONArray("attachments") ?: JSONArray()
        for (index in 0 until attachments.length()) {
            addImageFile(attachments.optJSONObject(index))?.let { add(it) }
        }
    }.distinctBy { it.node to it.path }

    private fun addImageFile(file: JSONObject?): MediaFile? {
        val path = file?.optString("path")?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { URI(path) }.getOrNull()
        if (uri?.isAbsolute == true && !uri.host.equals(URI(baseUrl).host, ignoreCase = true)) return null
        val pathOnly = (uri?.path ?: path).removePrefix("/data/")
        if (pathOnly.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) return null
        val node = file.optInt("node", 0).takeIf { it > 0 } ?: return null
        return MediaFile(pathOnly, node)
    }

    private fun mediaUrl(file: MediaFile): String =
        "https://n${file.node}.pawchive.pw/data/${file.path.trimStart('/')}"

    private companion object {
        const val PAGE_SIZE = 50
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    }
}
