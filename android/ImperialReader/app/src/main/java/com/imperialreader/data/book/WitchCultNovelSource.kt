package com.imperialreader.data.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class WitchCultNovelSource : BookSource {
    override val id = "novel_witchculttranslation_com"
    override val name = "Witch Cult Translations"
    override val webUrl = "https://witchculttranslation.com"
    override val description = "Re:Zero web novel arcs and translations"
    override val isWebNovel = true

    override suspend fun popular(page: Int): List<BookItem> =
        if (page == 1) parseArcs(fetchHtml(TOC_URL)) else emptyList()

    override suspend fun search(query: String, page: Int): List<BookItem> {
        if (query.isBlank()) return emptyList()
        return popular(page).filter {
            it.title.contains(query.trim(), true) || name.contains(query.trim(), true)
        }
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val arc = Regex("""#arc-(\d+)$""").find(item.id)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Invalid Witch Cult arc")
        val chapters = parseChapters(fetchHtml(TOC_URL), arc)
        if (chapters.isEmpty()) throw IOException("Witch Cult returned no readable chapters for ${item.title}")
        return chapters
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val host = runCatching { URI(chapter.url).host?.lowercase() }.getOrNull()
        val selector = when (host) {
            "witchculttranslation.com", "kagurojp.wordpress.com" -> "article .entry-content"
            "eminenttranslations.com" -> ".reader-paged-content"
            "translationchicken.com" -> "article .post-content"
            else -> throw IOException("Unsupported Witch Cult chapter host")
        }
        val content = Jsoup.parse(fetchHtml(chapter.url), chapter.url).selectFirst(selector)
            ?: throw IOException("Witch Cult chapter text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form").remove()
        if (content.select("p").none { it.text().isNotBlank() }) {
            throw IOException("Witch Cult returned an empty chapter")
        }
        return content.html()
    }

    companion object {
        private const val TOC_URL = "https://witchculttranslation.com/table-of-content/"
        private val arcHeading = Regex("""(?i)^arc\s+(\d+)\b""")
        private val supportedHosts = setOf(
            "witchculttranslation.com", "eminenttranslations.com",
            "kagurojp.wordpress.com", "translationchicken.com"
        )

        fun parseArcs(html: String): List<BookItem> {
            val content = Jsoup.parse(html, TOC_URL).selectFirst("article .entry-content")
                ?: throw IOException("Witch Cult table of contents was not found")
            return content.children().mapNotNull { element ->
                if (element.tagName() != "h1") return@mapNotNull null
                val heading = element.text().trim()
                val number = arcHeading.find(heading)?.groupValues?.get(1) ?: return@mapNotNull null
                if (parseChapters(content, number.toInt()).none {
                        it.title.startsWith("Chapter", true) || it.title.startsWith("Prologue", true)
                    }
                ) return@mapNotNull null
                BookItem(
                    sourceId = "novel_witchculttranslation_com",
                    sourceName = "Witch Cult Translations",
                    id = "$TOC_URL#arc-$number",
                    title = "Re:Zero — $heading",
                    author = "Tappei Nagatsuki",
                    coverUrl = null,
                    epubUrl = null
                )
            }
        }

        fun parseChapters(html: String, arc: Int): List<NovelChapter> {
            val content = Jsoup.parse(html, TOC_URL).selectFirst("article .entry-content")
                ?: throw IOException("Witch Cult table of contents was not found")
            return parseChapters(content, arc)
        }

        private fun parseChapters(content: Element, arc: Int): List<NovelChapter> {
            var current = 0
            val chapters = mutableListOf<NovelChapter>()
            for (element in content.children()) {
                if (element.tagName() == "h1") {
                    arcHeading.find(element.text().trim())?.groupValues?.get(1)?.toIntOrNull()?.let { current = it }
                }
                if (current != arc || element.tagName() !in setOf("ul", "ol")) continue
                for (link in element.select("li a[href]")) {
                    val url = link.absUrl("href")
                    val uri = runCatching { URI(url) }.getOrNull() ?: continue
                    val title = link.text().trim()
                    if (uri.scheme == "https" && uri.host?.lowercase() in supportedHosts &&
                        !uri.path.orEmpty().endsWith(".pdf", true) && title.isNotBlank()
                    ) {
                        chapters.add(NovelChapter(title, url))
                    }
                }
            }
            return chapters.distinctBy { it.url }
        }
    }
}
