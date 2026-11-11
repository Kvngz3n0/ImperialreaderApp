package com.imperialreader.data.source

import android.graphics.BitmapFactory
import com.imperialreader.App
import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ToonkotsuSource : HttpSource() {
    override val id = "toonkotsu_com"
    override val name = "Toonkotsu"
    override val lang = "en"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int) = browse("sort=popular", page)
    override suspend fun getLatest(page: Int) = browse("sort=new", page)
    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false) else browse("q=${enc(query.trim())}", page)

    private suspend fun browse(parameters: String, page: Int): MangasPage {
        require(page > 0)
        return parseCatalog(JSONObject(getString("$BASE/cdn/v1/browse?sfw=true&limit=24&page=$page&$parameters")), page)
    }

    private fun seriesCode(url: String): String {
        val uri = URI(url)
        val match = SERIES.matchEntire(uri.path.orEmpty())
        if (uri.scheme != "https" || uri.host != HOST || match == null) throw IOException("Invalid Toonkotsu title URL")
        return match.groupValues[1]
    }

    private suspend fun series(url: String): JSONObject =
        JSONObject(getString("$BASE/cdn/v1/series/${seriesCode(url)}"))

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val item = series(manga.url).optJSONObject("series") ?: throw IOException("Toonkotsu did not return title details")
        item.optString("title").takeIf(String::isNotBlank)?.let { manga.title = it }
        item.optString("description").takeIf(String::isNotBlank)?.let { manga.description = it }
        manga.thumbnailUrl = imageUrl(item.optString("cover")) ?: manga.thumbnailUrl
        manga.author = stringList(item.optJSONArray("authors"))
        manga.genre = stringList(item.optJSONArray("tags"))
        manga.status = statusFromText(item.optString("status"))
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val data = series(manga.url).optJSONArray("chapters") ?: throw IOException("Toonkotsu did not return chapters")
        return parseChapters(data, manga.url).also {
            if (it.isEmpty()) throw IOException("Toonkotsu returned no chapters for ${manga.title}")
        }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val uri = URI(chapter.url)
        val match = CHAPTER.matchEntire(uri.path.orEmpty())
        if (uri.scheme != "https" || uri.host != HOST || match == null) throw IOException("Invalid Toonkotsu chapter URL")
        val response = JSONObject(getString("$BASE/api/manga/chapter/${match.groupValues[1]}"))
        val data = response.optJSONObject("chapter") ?: throw IOException("Toonkotsu did not return chapter data")
        val pages = parsePages(data)
        val created = mutableListOf<File>()
        try {
            return pages.map { page ->
                if (page.key == null) page.url else {
                    val file = downloadPage(page)
                    created += file
                    file.toURI().toString()
                }
            }
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }

    private suspend fun downloadPage(page: ReaderPage): File = withContext(Dispatchers.IO) {
        val directory = File(App.instance.cacheDir, PAGE_CACHE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the Toonkotsu page cache")
        }
        val file = File.createTempFile("page-", ".img", directory)
        try {
            val request = Request.Builder().url(page.url).header("Referer", "$BASE/").build()
            client.await(request).use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code, page.url)
                val body = response.body ?: throw IOException("Toonkotsu returned an empty image")
                val cipher = pageCipher(page.key!!, page.nonce!!)
                CipherInputStream(body.byteStream(), cipher).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null) {
                throw IOException("Toonkotsu returned an invalid decrypted image")
            }
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    companion object {
        internal const val PAGE_CACHE_DIRECTORY = "toonkotsu-pages"
        private const val HOST = "toonkotsu.com"
        private const val BASE = "https://$HOST"
        private val SERIES = Regex("""^/title/([A-Za-z0-9]+)(?:-[A-Za-z0-9-]+)?/?$""")
        private val CHAPTER = Regex("""^/title/[A-Za-z0-9-]+/(\d+)-chapter-[A-Za-z0-9.-]+/?$""")

        private fun imageUrl(value: String): String? {
            val url = if (value.startsWith("/") && !value.startsWith("//")) BASE + value else value
            val uri = runCatching { URI(url) }.getOrNull()
            return url.takeIf { uri?.scheme == "https" && !uri.host.isNullOrBlank() }
        }

        private fun stringList(values: JSONArray?): String? = values?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank).joinToString(", ").ifBlank { null }
        }

        fun parseCatalog(data: JSONObject, page: Int): MangasPage {
            val list = data.optJSONArray("items") ?: throw IOException("Toonkotsu returned an invalid catalog")
            val entries = (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                if (item.optBoolean("adult", false)) return@mapNotNull null
                val code = item.optString("code").takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) } ?: return@mapNotNull null
                val slug = item.optString("slug").takeIf { it.matches(Regex("""[A-Za-z0-9-]+""")) } ?: return@mapNotNull null
                val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                SManga("$BASE/title/$code-$slug", title, thumbnailUrl = imageUrl(item.optString("cover")))
            }.distinctBy { it.url }
            val next = if (data.has("hasNextPage")) data.optBoolean("hasNextPage")
                else if (data.has("total")) page.toLong() * data.optInt("limit", 24) < data.optLong("total")
                else list.length() >= 24
            return MangasPage(entries, next)
        }

        fun parseChapters(data: JSONArray, titleUrl: String): List<SChapter> =
            (0 until data.length()).mapNotNull { i ->
                val item = data.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                val n = item.optString("n").toFloatOrNull() ?: -1f
                val label = item.optString("label").takeIf(String::isNotBlank) ?: "Chapter ${if (n < 0) "0" else n.toString().removeSuffix(".0")}"
                val pathNumber = if (n < 0) "0" else n.toString().removeSuffix(".0")
                SChapter("${titleUrl.trimEnd('/')}/$id-chapter-$pathNumber", label, chapterNumber = n,
                    scanlator = item.optJSONObject("group")?.optString("name")?.takeIf(String::isNotBlank))
            }.distinctBy { it.url }.sortedByDescending { it.chapterNumber }

        internal data class ReaderPage(val url: String, val key: String?, val nonce: String?)

        internal fun pageCipher(key: String, nonce: String): Cipher {
            val keyBytes: ByteArray
            val nonceBytes: ByteArray
            try {
                keyBytes = Base64.getUrlDecoder().decode(key)
                nonceBytes = Base64.getUrlDecoder().decode(nonce)
            } catch (e: IllegalArgumentException) {
                throw IOException("Toonkotsu returned invalid encryption metadata", e)
            }
            if (keyBytes.size !in listOf(16, 24, 32) || nonceBytes.isEmpty() || nonceBytes.size > 16) {
                throw IOException("Toonkotsu returned invalid encryption metadata")
            }
            return Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(nonceBytes.copyOf(16)))
            }
        }

        internal fun parsePages(chapter: JSONObject): List<ReaderPage> {
            val pages = chapter.optJSONArray("pages") ?: throw IOException("Toonkotsu did not return reader pages")
            return (0 until pages.length()).map { index ->
                val page = pages.optJSONObject(index) ?: throw IOException("Toonkotsu returned an invalid page")
                val url = imageUrl(page.optString("src")) ?: throw IOException("Toonkotsu returned an invalid image URL")
                val key = page.optString("k").takeIf(String::isNotBlank)
                val nonce = page.optString("n").takeIf(String::isNotBlank)
                if ((key == null) != (nonce == null)) throw IOException("Toonkotsu returned incomplete encryption metadata")
                if (key != null) {
                    val uri = URI(url)
                    if (uri.host != HOST || !uri.path.startsWith("/cdn/e/")) {
                        throw IOException("Toonkotsu returned an invalid encrypted image URL")
                    }
                }
                ReaderPage(url, key, nonce)
            }.also { if (it.isEmpty()) throw IOException("Toonkotsu returned no reader pages") }
        }
    }
}
