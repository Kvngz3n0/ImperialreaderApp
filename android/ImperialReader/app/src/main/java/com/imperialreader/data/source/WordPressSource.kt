package com.imperialreader.data.source

import com.imperialreader.data.network.CloudflareException

/**
 * A site whose engine we don't know in advance. On first use it looks at the home page and picks the
 * Madara engine, the MangaThemesia engine, or (for everything else, any domain) the pattern-based GenericSource,
 * then forwards every call to it.
 */
class WordPressSource(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
    override val nsfw: Boolean = false
) : HttpSource() {

    override val id: String = "wp_" + hostId(baseUrl, name)

    @Volatile
    private var cached: Source? = null

    private suspend fun engine(): Source {
        cached?.let { return it }
        val html = try {
            getString("$baseUrl/")
        } catch (e: CloudflareException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        val lower = html.lowercase()
        val madara = "wp-manga" in lower || "madara" in lower
        val themesia = !madara && ("themesia" in lower || "ts_reader" in lower || "class=\"bsx\"" in lower || "listupd" in lower)
        val chosen: Source = when {
            madara -> MadaraSource(name, baseUrl, lang)
            themesia -> MangaThemesiaSource(name, baseUrl, lang)
            else -> GenericSource(name, baseUrl, lang, nsfw)
        }
        if (html.isNotBlank()) cached = chosen
        return chosen
    }

    override fun imageHeaders(): Map<String, String> = cached?.imageHeaders() ?: extraHeaders

    override suspend fun getPopular(page: Int) = engine().getPopular(page)
    override suspend fun getLatest(page: Int) = engine().getLatest(page)
    override suspend fun search(query: String, page: Int) = engine().search(query, page)
    override suspend fun getMangaDetails(manga: SManga) = engine().getMangaDetails(manga)
    override suspend fun getChapters(manga: SManga) = engine().getChapters(manga)
    override suspend fun getPages(chapter: SChapter) = engine().getPages(chapter)
}
