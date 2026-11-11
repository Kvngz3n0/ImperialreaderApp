package com.imperialreader.data.book

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

class BakaTsukiNovelSource : BookSource {
    override val id = "novel_baka_tsuki_org"
    override val name = "Baka-Tsuki"
    override val webUrl = BASE
    override val description = "English translation projects"
    override val isWebNovel = true

    private val mutex = Mutex()
    private var catalog: List<BookItem>? = null
    private val available = mutableMapOf<String, Boolean>()

    override suspend fun popular(page: Int): List<BookItem> = find("", page)

    override suspend fun search(query: String, page: Int): List<BookItem> =
        if (query.isBlank()) emptyList() else find(query.trim(), page)

    private suspend fun find(query: String, page: Int): List<BookItem> {
        if (page < 1) return emptyList()
        return mutex.withLock {
            val entries = catalog ?: parseCatalog(fetchHtml(CATEGORY_URL)).also { catalog = it }
            val matching = entries.filter { it.title.contains(query, true) }
            val wanted = page * PAGE_SIZE
            val results = mutableListOf<BookItem>()
            for (batch in matching.chunked(4)) {
                val checked = coroutineScope {
                    batch.map { item ->
                        async {
                            val cached = available[item.id]
                            item to (cached ?: parseChapters(fetchHtml(item.id), item.id).isNotEmpty())
                        }
                    }.awaitAll()
                }
                checked.forEach { (item, hasChapters) ->
                    available[item.id] = hasChapters
                    if (hasChapters) results.add(item)
                }
                if (results.size >= wanted) break
            }
            results.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        }
    }

    override suspend fun details(item: BookItem): BookItem {
        val doc = Jsoup.parse(fetchHtml(item.id), item.id)
        val cover = doc.select("#mw-content-text .mw-parser-output img[width]")
            .firstOrNull { (it.attr("width").toIntOrNull() ?: 0) >= 100 }
            ?.absUrl("src")?.takeIf { it.isNotBlank() }
        return item.copy(coverUrl = cover ?: item.coverUrl)
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> =
        parseChapters(fetchHtml(item.id), item.id).also {
            if (it.isEmpty()) throw IOException("Baka-Tsuki has no available chapters for ${item.title}")
        }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        if (URI(chapter.url).host != "www.baka-tsuki.org") throw IOException("Invalid Baka-Tsuki chapter URL")
        val content = Jsoup.parse(fetchHtml(chapter.url), chapter.url)
            .selectFirst("#mw-content-text .mw-parser-output")
            ?: throw IOException("Baka-Tsuki chapter text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, form, .printfooter, .navbox").remove()
        if (content.select("p").none { it.text().isNotBlank() }) throw IOException("Baka-Tsuki chapter is empty")
        return content.html()
    }

    companion object {
        private const val BASE = "https://www.baka-tsuki.org"
        private const val CATEGORY_URL = "$BASE/project/index.php?title=Category:Light_novel_(English)"
        private const val PAGE_SIZE = 20
        private val chapterName = Regex("""(?i)\b(chapter|prologue|epilogue|afterword|interlude|side story|short story)\b""")

        fun parseCatalog(html: String): List<BookItem> {
            val doc = Jsoup.parse(html, CATEGORY_URL)
            val container = doc.selectFirst("#mw-pages") ?: throw IOException("Baka-Tsuki catalog was not found")
            val items = container.select("li a[href]").mapNotNull { link ->
                val url = link.absUrl("href")
                val title = link.text().trim()
                if (title.isBlank() || URI(url).host != "www.baka-tsuki.org") null else
                    BookItem("novel_baka_tsuki_org", "Baka-Tsuki", url, title, null, null, null)
            }.distinctBy { it.id }
            if (items.isEmpty()) throw IOException("Baka-Tsuki catalog was empty")
            return items
        }

        fun parseChapters(html: String, projectUrl: String): List<NovelChapter> {
            val projectTitle = wikiTitle(projectUrl) ?: throw IOException("Invalid Baka-Tsuki project URL")
            val content = Jsoup.parse(html, projectUrl).selectFirst("#mw-content-text .mw-parser-output")
                ?: throw IOException("Baka-Tsuki project content was not found")
            return content.select("li a[href]:not(.new)").mapNotNull { link ->
                val url = link.absUrl("href")
                val linkTitle = wikiTitle(url)
                val label = link.text().trim()
                if (URI(url).host != "www.baka-tsuki.org" ||
                    linkTitle == null || !linkTitle.startsWith("$projectTitle:", true) ||
                    !chapterName.containsMatchIn(label) || label.isBlank()
                ) null else NovelChapter(label, url)
            }.distinctBy { it.url }
        }

        private fun wikiTitle(url: String): String? {
            val query = URI(url).rawQuery ?: return null
            val value = query.split('&').firstOrNull { it.startsWith("title=") }?.substringAfter('=')
                ?: return null
            return URLDecoder.decode(value, "UTF-8").replace('_', ' ')
        }
    }
}
