package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class MangaDotSource : HttpSource() {
    override val id = "mangadot_net"
    override val name = "MangaDot"
    override val lang = "en"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int): MangasPage = catalog("most-tracked", page)

    override suspend fun getLatest(page: Int): MangasPage = catalog("latest-updates", page)

    private suspend fun catalog(mode: String, page: Int): MangasPage {
        require(page > 0)
        val url = "$BASE/view-all/$mode.data?adult=0&page=$page&_routes=pages%2FViewAllPage"
        return parseCatalog(getString(url, XHR))
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        require(page > 0)
        if (query.isBlank()) return MangasPage(emptyList(), false)
        val url = "$BASE/api/search?lang=en&search=${enc(query.trim())}&page=$page&limit=56"
        return parseSearch(JSONObject(getString(url, XHR)))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val id = mangaId(manga.url)
        val url = "$BASE/manga/$id.data?_routes=pages%2FMangaDetailPage"
        val details = parseDetails(getString(url, XHR))
        details.title.takeIf(String::isNotBlank)?.let { manga.title = it }
        details.thumbnailUrl?.let { manga.thumbnailUrl = it }
        manga.description = details.description
        manga.author = details.author
        manga.artist = details.artist
        manga.genre = details.genre
        manga.status = details.status
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val url = "$BASE/api/manga/${mangaId(manga.url)}/chapters/list?lang=en"
        val chapters = parseChapters(JSONArray(getString(url, XHR)))
        if (chapters.isEmpty()) throw IOException("MangaDot returned no English chapters for ${manga.title}")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val uri = URI(chapter.url)
        val match = CHAPTER_PATH.matchEntire(uri.path.orEmpty())
        if (uri.scheme != "https" || uri.host != HOST || match == null ||
            (uri.rawQuery != null && uri.rawQuery != "source=user")
        ) throw IOException("Invalid MangaDot chapter URL")
        val type = if (uri.rawQuery == "source=user") "uploads" else "chapters"
        val data = JSONObject(getString("$BASE/api/$type/${match.groupValues[1]}/images", XHR))
        return parsePages(data).also {
            if (it.isEmpty()) throw IOException("MangaDot returned no images for ${chapter.name}")
        }
    }

    companion object {
        private const val HOST = "mangadot.net"
        private const val BASE = "https://$HOST"
        private val XHR = mapOf("X-Requested-With" to "XMLHttpRequest")
        private val MANGA_PATH = Regex("""^/manga/(\d+)/?$""")
        private val CHAPTER_PATH = Regex("""^/chapter/(\d+)/?$""")

        private fun mangaId(url: String): String {
            val uri = URI(url)
            val match = MANGA_PATH.matchEntire(uri.path.orEmpty())
            if (uri.scheme != "https" || uri.host != HOST || match == null) {
                throw IOException("Invalid MangaDot series URL")
            }
            return match.groupValues[1]
        }

        private fun imageUrl(value: String): String? {
            val url = when {
                value.startsWith("/") && !value.startsWith("//") -> BASE + value
                value.startsWith("https://") -> value
                else -> return null
            }
            val uri = runCatching { URI(url) }.getOrNull()
            return url.takeIf { uri?.scheme == "https" && !uri.host.isNullOrBlank() }
        }

        private fun adult(item: JSONObject): Boolean =
            listOf("is_adult", "is_blurworthy").any { key ->
                item.optBoolean(key, false) || item.optString(key) == "1"
            }

        private fun entry(item: JSONObject): SManga? {
            if (adult(item)) return null
            val id = item.optLong("id", item.optLong("manga_id")).takeIf { it > 0 } ?: return null
            val title = item.optString("title").trim().takeIf(String::isNotBlank) ?: return null
            return SManga("$BASE/manga/$id", title, thumbnailUrl = imageUrl(item.optString("photo")))
        }

        fun parseCatalog(json: String): MangasPage {
            val table = IndexedResponse(JSONArray(json))
            val root = table.field(table.field(table.root(), "pages/ViewAllPage"), "data")
            val data = table.field(root, "data")
            val entries = table.array(table.field(data, "manga_list"))
                .mapNotNull { entry(table.objectValue(it)) }.distinctBy { it.url }
            val pagination = table.objectValue(table.field(data, "pagination"))
            return MangasPage(entries, pagination.optInt("current_page") < pagination.optInt("total_pages"))
        }

        fun parseSearch(data: JSONObject): MangasPage {
            val entries = data.optJSONArray("results") ?: data.optJSONArray("manga_list")
                ?: throw IOException("MangaDot returned an invalid search response")
            val matches = (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.let(::entry) }
                .distinctBy { it.url }
            val pagination = data.optJSONObject("pagination")
                ?: throw IOException("MangaDot did not return search pagination")
            val current = pagination.optInt("current_page", pagination.optInt("currentPage", pagination.optInt("page", -1)))
            val total = pagination.optInt("total_pages", pagination.optInt("totalPages",
                pagination.optInt("last_page", pagination.optInt("lastPage", -1))))
            val nextCursor = listOf("next_cursor", "nextCursor", "cursor")
                .map { pagination.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
            if ((current < 0 || total < 0) && nextCursor == null) {
                throw IOException("MangaDot returned invalid search pagination")
            }
            return MangasPage(matches, if (current >= 0 && total >= 0) current < total else nextCursor != null)
        }

        fun parseDetails(json: String): SManga {
            val table = IndexedResponse(JSONArray(json))
            val root = table.field(table.field(table.root(), "pages/MangaDetailPage"), "data")
            val details = table.field(table.field(root, "mangaData"), "manga")
            val item = table.objectValue(details)
            if (adult(item)) throw IOException("MangaDot returned an adult title")
            val id = item.optLong("id").takeIf { it > 0 } ?: throw IOException("MangaDot returned an invalid series")
            val title = item.optString("title").takeIf(String::isNotBlank)
                ?: throw IOException("MangaDot returned a series without a title")
            val genres = item.optJSONArray("genres")
            return SManga("$BASE/manga/$id", title, imageUrl(item.optString("photo")),
                author = item.optString("authors").takeIf(String::isNotBlank),
                artist = item.optString("artists").takeIf(String::isNotBlank),
                description = item.optString("description").takeIf(String::isNotBlank),
                genre = genres?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank).joinToString(", ") },
                status = statusFromText(item.optString("status")))
        }

        fun parseChapters(data: JSONArray): List<SChapter> =
            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val language = item.optString("language").trim()
                if (language.isNotBlank() && !language.equals("en", true)) return@mapNotNull null
                val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                val source = item.optString("source", "user")
                val url = "$BASE/chapter/$id" + if (source == "user") "?source=user" else ""
                val number = item.optString("chapter_number").toFloatOrNull() ?: -1f
                val title = item.optString("chapter_title").trim()
                val label = if (number >= 0) "Chapter ${item.optString("chapter_number").removeSuffix(".0")}" else "Chapter"
                val name = if (title.isBlank()) label else "$label - $title"
                SChapter(url, name, chapterNumber = number,
                    scanlator = item.optString("group_name").takeIf(String::isNotBlank)
                        ?: item.optString("scanlator_name").takeIf(String::isNotBlank))
            }.distinctBy { it.url }.reversed()

        fun parsePages(data: JSONObject): List<String> {
            val images = data.optJSONArray("images") ?: throw IOException("MangaDot did not return page images")
            return (0 until images.length()).mapNotNull { index ->
                images.optJSONObject(index)?.optString("url")?.let(::imageUrl)
            }
        }

        private class IndexedResponse(private val table: JSONArray) {
            fun root(): Any = value(0)

            private fun value(index: Int): Any {
                if (index < 0) return JSONObject.NULL
                if (index >= table.length()) throw IOException("MangaDot returned an invalid indexed response")
                return table.get(index)
            }

            fun field(node: Any, name: String): Any {
                val obj = node as? JSONObject ?: throw IOException("MangaDot returned an invalid response object")
                for (key in obj.keys()) {
                    val index = key.removePrefix("_").toIntOrNull() ?: continue
                    if (value(index) == name) return value(obj.getInt(key))
                }
                throw IOException("MangaDot response is missing $name")
            }

            fun array(node: Any): List<Any> {
                val array = node as? JSONArray ?: throw IOException("MangaDot returned an invalid response list")
                return (0 until array.length()).map { value(array.getInt(it)) }
            }

            fun objectValue(node: Any): JSONObject {
                val source = node as? JSONObject ?: throw IOException("MangaDot returned an invalid response object")
                val result = JSONObject()
                for (key in source.keys()) {
                    val field = key.removePrefix("_").toIntOrNull() ?: continue
                    val name = value(field) as? String ?: continue
                    val raw = value(source.getInt(key))
                    result.put(name, if (raw is JSONArray) JSONArray(array(raw)) else raw)
                }
                return result
            }
        }
    }
}
