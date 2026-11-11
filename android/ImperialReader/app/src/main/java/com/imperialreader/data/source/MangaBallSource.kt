package com.imperialreader.data.source

import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class MangaBallSource : HttpSource() {
    override val id = "mangaball_com"
    override val name = "MangaBall"
    override val lang = "en"
    override val baseUrl = BASE
    private val xhr = mapOf("X-Requested-With" to "XMLHttpRequest")

    override suspend fun getPopular(page: Int) = catalog("sort_by=views&sort_order=desc", page)
    override suspend fun getLatest(page: Int) = catalog("sort_by=lastupdate&sort_order=desc", page)
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false) else catalog("keyword=${enc(query.trim())}", page)

    private suspend fun catalog(filter: String, page: Int): MangasPage {
        require(page > 0)
        val url = "$BASE/api/v1/title/search-advanced?page=$page&limit=24&tag_mode=AND&adult_mode=no_18&chapterLanguage=en&$filter"
        return parseCatalog(JSONObject(getString(url, xhr)))
    }

    private fun id(url: String, prefix: String): String {
        val uri = URI(url)
        val match = Regex("^/$prefix/([A-Za-z0-9_-]+)/?$").matchEntire(uri.path.orEmpty())
        if (uri.scheme != "https" || uri.host != HOST || match == null) throw IOException("Invalid MangaBall URL")
        return match.groupValues[1]
    }

    private suspend fun details(url: String): JSONObject =
        JSONObject(getString("$BASE/api/v1/title/detail/${id(url, "title-detail")}", xhr))
            .optJSONObject("data") ?: throw IOException("MangaBall did not return title details")

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val item = details(manga.url)
        item.optString("name").takeIf(String::isNotBlank)?.let { manga.title = it }
        cover(item)?.let { manga.thumbnailUrl = it }
        manga.description = strings(item.optJSONArray("description"))
        manga.author = names(item.optJSONArray("author"))
        manga.genre = names(item.optJSONArray("tags"))
        manga.status = statusFromText(item.optString("status"))
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val titleId = details(manga.url).optString("id").takeIf { it.matches(IDENTIFIER) }
            ?: throw IOException("MangaBall returned an invalid title ID")
        val body = chapterRequestBody(titleId)
        val url = "$BASE/api/v1/chapter/chapter-listing-by-title-id"
        val request = Request.Builder().url(url).header("Referer", "$BASE/")
            .header("X-Requested-With", "XMLHttpRequest").post(body).build()
        val text = withContext(Dispatchers.IO) {
            client.await(request).use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code, url)
                response.body?.string() ?: throw IOException("MangaBall returned an empty chapter response")
            }
        }
        val data = JSONObject(text).optJSONArray("data") ?: throw IOException("MangaBall did not return chapters")
        return parseChapters(data).also { if (it.isEmpty()) throw IOException("MangaBall returned no English chapters for ${manga.title}") }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val chapterId = id(chapter.url, "chapter-detail")
        val data = JSONObject(getString("$BASE/api/v1/chapter-detail?chapter_id=$chapterId", xhr))
            .optJSONObject("data")?.optJSONObject("chapter")?.optJSONArray("pages")
            ?: throw IOException("MangaBall did not return reader pages")
        return (0 until data.length()).map { index ->
            val url = data.optString(index)
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw IOException("MangaBall returned an invalid image URL")
            url
        }.also { if (it.isEmpty()) throw IOException("MangaBall returned no reader images") }
    }

    companion object {
        private const val HOST = "mangaball.com"
        private const val BASE = "https://$HOST"
        private val IDENTIFIER = Regex("""[A-Za-z0-9_-]+""")

        internal fun chapterRequestBody(titleId: String) =
            JSONObject().put("title_id", titleId).toString().toByteArray(Charsets.UTF_8)
                .toRequestBody("application/json".toMediaType())

        private fun strings(a: JSONArray?): String? = a?.let {
            (0 until it.length()).map { i -> it.optString(i) }.filter(String::isNotBlank).joinToString("\n\n").ifBlank { null }
        }
        private fun names(a: JSONArray?): String? = a?.let {
            (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString(", ").ifBlank { null }
        }
        private fun cover(item: JSONObject): String? {
            val image = item.optJSONObject("image") ?: return null
            val path = image.optJSONObject("cover")?.optString("path")?.replace('\\', '/')
            val url = if (!path.isNullOrBlank()) {
                if (path.startsWith("https://")) path else "https://bulbasaur.poke-black-and-white.net/covers/$path"
            } else listOf("file", "cdn_mangadex", "cdn_mangaupdate", "cdn_mangaupdates")
                .map { image.optString(it) }.firstOrNull(String::isNotBlank)
            val uri = runCatching { URI(url ?: return null) }.getOrNull()
            return url.takeIf { uri?.scheme == "https" && !uri.host.isNullOrBlank() }
        }

        fun parseCatalog(data: JSONObject): MangasPage {
            val list = data.optJSONArray("data") ?: throw IOException("MangaBall returned an invalid catalog")
            val pagination = data.optJSONObject("pagination") ?: throw IOException("MangaBall returned invalid pagination")
            if (!pagination.has("page") || !pagination.has("total_pages")) throw IOException("MangaBall returned invalid pagination")
            val entries = (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.matches(IDENTIFIER) } ?: return@mapNotNull null
                val title = item.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
                SManga("$BASE/title-detail/$id", title, thumbnailUrl = cover(item))
            }.distinctBy { it.url }
            return MangasPage(entries, pagination.optInt("page") < pagination.optInt("total_pages"))
        }

        fun parseChapters(data: JSONArray): List<SChapter> =
            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                if (!item.optString("lang").equals("en", true)) return@mapNotNull null
                val id = item.optString("id").takeIf { it.matches(IDENTIFIER) } ?: return@mapNotNull null
                val number = item.optString("number").toFloatOrNull() ?: -1f
                val label = item.optString("name").takeIf(String::isNotBlank)
                val name = listOf(if (number >= 0) "Chapter ${number.toString().removeSuffix(".0")}" else "Chapter", label)
                    .filterNotNull().joinToString(" - ")
                SChapter("$BASE/chapter-detail/$id", name, chapterNumber = number,
                    scanlator = item.optJSONObject("group")?.optString("name")?.takeIf(String::isNotBlank))
            }.distinctBy { it.url }.sortedByDescending { it.chapterNumber }
    }
}
