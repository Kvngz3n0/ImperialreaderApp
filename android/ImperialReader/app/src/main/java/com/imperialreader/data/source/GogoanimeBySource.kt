package com.imperialreader.data.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

class GogoanimeBySource : AnimeHttpSource() {
    override val id = "anime_gogoanime_by"
    override val name = "Gogoanime"
    override val baseUrl = BASE

    override suspend fun getPopular(page: Int): MangasPage =
        catalog("$BASE/series/?order=popular&page=$page")

    override suspend fun getLatest(page: Int): MangasPage =
        catalog("$BASE/series/?order=update&page=$page")

    override suspend fun search(query: String, page: Int): MangasPage =
        if (query.isBlank()) MangasPage(emptyList(), false)
        else catalog("$BASE/?s=${enc(query.trim())}&paged=$page")

    private suspend fun catalog(url: String): MangasPage =
        parseCatalog(Jsoup.parse(getString(url), url))

    override suspend fun getMangaDetails(manga: SManga): SManga {
        requireSiteUrl(manga.url, "/series/")
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { manga.title = it }
        doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let { manga.thumbnailUrl = it }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        requireSiteUrl(manga.url, "/series/")
        return parseEpisodes(Jsoup.parse(getString(manga.url), manga.url)).also {
            if (it.isEmpty()) throw IOException("Gogoanime returned no episodes for ${manga.title}")
        }
    }

    override suspend fun getVideoOptions(chapter: SChapter): List<VideoOption> {
        requireSiteUrl(chapter.url, "/")
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        if (doc.selectFirst(".player-type-link[data-src]") == null) {
            throw IOException("Gogoanime did not provide a player for this episode")
        }
        return listOf(VideoOption("Site player", false, useWebView = true) { loadEpisode(chapter) })
    }

    override suspend fun loadEpisode(episode: SChapter): ChapterPages {
        requireSiteUrl(episode.url, "/")
        return ChapterPages(listOf(episode.url), imageHeaders())
    }

    override suspend fun resolveDownload(chapter: SChapter, dub: Boolean): ChapterPages =
        throw IOException("Gogoanime episodes use the site player and cannot be downloaded by the app")

    companion object {
        private const val BASE = "https://gogoanime.by"

        private fun requireSiteUrl(url: String, prefix: String) {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "gogoanime.by" || !uri.path.orEmpty().startsWith(prefix)) {
                throw IOException("Invalid Gogoanime URL")
            }
        }

        fun parseCatalog(doc: Document): MangasPage {
            val entries = doc.select("article.bs .bsx a[href]").mapNotNull { link ->
                val url = link.absUrl("href")
                val uri = runCatching { URI(url) }.getOrNull()
                val title = link.attr("title").trim()
                if (uri?.scheme != "https" || uri.host != "gogoanime.by" ||
                    !uri.path.orEmpty().startsWith("/series/") || title.isBlank()
                ) null else SManga(url, title, thumbnailUrl = link.selectFirst("img[src]")?.absUrl("src"))
            }.distinctBy { it.url }
            if (entries.isEmpty() && doc.selectFirst("article.bs") != null) {
                throw IOException("Gogoanime returned an unsupported series catalog")
            }
            return MangasPage(entries, doc.selectFirst(".hpage a[href]:containsOwn(Next), a.next.page-numbers[href]") != null)
        }

        fun parseEpisodes(doc: Document): List<SChapter> =
            doc.select(".episodes-container .episode-item a[href]").mapNotNull { link ->
                val url = link.absUrl("href")
                val uri = runCatching { URI(url) }.getOrNull()
                val title = link.text().trim()
                if (uri?.scheme != "https" || uri.host != "gogoanime.by" || title.isBlank()) null
                else SChapter(url, title)
            }.distinctBy { it.url }
    }
}
