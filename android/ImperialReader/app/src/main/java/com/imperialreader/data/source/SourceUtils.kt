package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

internal fun hostId(baseUrl: String, fallback: String): String =
    (runCatching { URI(baseUrl).host }.getOrNull() ?: fallback).lowercase().replace(Regex("[^a-z0-9]"), "_")

/** Returns the first usable (lazy-loaded or plain) image URL of an <img>, made absolute. */
internal fun lazyImage(img: Element?): String? {
    if (img == null) return null
    for (attr in listOf("data-src", "data-lazy-src", "data-cfsrc", "data-original", "src")) {
        val u = img.absUrl(attr)
        if (u.isNotBlank() && !u.startsWith("data:")) return u
    }
    return null
}

private val GENRE_STOP_WORDS = setOf(
    "genre", "genres", "genre(s)", "tag", "tags", "all", "more", "show more", "next", "prev", "previous", "home",
    "read", "read more", "view all", "see all", "latest", "popular", "trending", "new", "hot", "top", "search",
    "none", "n/a", "unknown", "-", "login", "sign in", "sign up", "bookmark", "bookmarks", "comments"
)
private val GENRE_SEO_WORDS = Regex("""(?i)\b(read|online|free|websites?|manga sites?|watch|download|best|streaming)\b""")
private val CHROME_TAG_NAMES = setOf("nav", "header", "footer", "aside")
private val CHROME_WORDS = Regex(
    """(?i)(^|[\s_-])(nav|navbar|menu|dropdown|sidebar|widget|related|similar|recommend|recommended|popular|trending|""" +
        """latest|footer|breadcrumb|comment|comments|search|filter|slider|carousel|ranking|bookmark|history)([\s_-]|$)"""
)
private val GENRE_LABEL = Regex("""(?i)^\s*(genres?|genre\(s\))\s*:?\s*$""")
private val TAG_LABEL = Regex("""(?i)^\s*(tags?|categories|category)\s*:?\s*$""")
private val INLINE_GENRE_LABEL = Regex("""(?i)^\s*(genres?|genre\(s\))\s*:\s*(.*)$""")
private val INLINE_TAG_LABEL = Regex("""(?i)^\s*(tags?|categories)\s*:\s*(.*)$""")
private val GENRE_CONTAINERS =
    ".genres-content a, .mgen a, .seriestugenre a, .genre-list a, .genres a, .genre a, [itemprop=genre], " +
        ".manga-genres a, .info-genres a, .post-genres a, .series-genres a"
private val TAG_CONTAINERS = ".tags a, .tag-list a, .tag-container a, .tags-content a, a.tag, .tag a"
private val LINK_PATTERNS =
    "a[href*=/genre/], a[href*=/genres/], a[href*=/manga-genre/], a[href*=genre=], a[href*=/tag/], a[href*=/tags/], a[href*=/manga-tag/]"

/** True when [el] sits in navigation, menus, sidebars or "related / trending" blocks rather than the entry's own details. */
private fun inPageChrome(el: Element): Boolean =
    generateSequence(el) { it.parent() }
        .filter { it.tagName() != "body" && it.tagName() != "html" && it.tagName() != "#root" }
        .any { it.tagName() in CHROME_TAG_NAMES || CHROME_WORDS.containsMatchIn(it.className()) || CHROME_WORDS.containsMatchIn(it.id()) }

private fun smartCase(value: String): String {
    if (!value.any(Char::isLetter)) return value
    if (value != value.lowercase() && value != value.uppercase()) return value
    val small = setOf("of", "the", "and", "in", "a", "an", "to", "for", "on")
    return value.lowercase().split(' ').mapIndexed { index, word ->
        if (index > 0 && word in small) word
        else word.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercase() } }
    }.joinToString(" ")
}

/** Cleans a raw list of tag/genre strings: splits joined values, drops junk, de-duplicates case-insensitively. */
internal fun normalizeGenreList(values: List<String>, limit: Int = 60): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (raw in values) {
        for (piece in raw.split(Regex("""\s*[,;|•·]\s*"""))) {
            var v = piece.replace('\u00A0', ' ').trim().trimStart('#').trim().trim('.', ':', '-', ' ')
            v = v.replace(Regex("""\s*\(\d[\d,.]*\)\s*$"""), "").replace(Regex("""\s{2,}"""), " ").trim()
            if (v.length < 2 || v.length > 40) continue
            if (v.lowercase() in GENRE_STOP_WORDS) continue
            if (v.all { it.isDigit() || it == ' ' }) continue
            if (v.contains("http", ignoreCase = true) || v.contains('@')) continue
            val shown = smartCase(v)
            if (seen.add(shown.lowercase())) out += shown
            if (out.size >= limit) return out
        }
    }
    return out
}

private fun anchorsAfterLabel(label: Element): List<String> {
    var current: Element? = label
    repeat(3) {
        val node = current ?: return emptyList()
        for (sibling in node.nextElementSiblings().take(2)) {
            val links = if (sibling.tagName() == "a") listOf(sibling) else sibling.select("a")
            if (links.isNotEmpty()) return links.map { it.text() }
        }
        current = node.parent()
    }
    return emptyList()
}

private fun labeled(doc: Document, labelRegex: Regex, inlineRegex: Regex): List<String> {
    val found = ArrayList<String>()
    for (el in doc.getAllElements()) {
        if (el.children().size > 40 || inPageChrome(el)) continue
        val own = el.ownText().trim()
        if (own.isEmpty() || own.length > 40) continue
        if (labelRegex.matches(own)) {
            found += anchorsAfterLabel(el)
        } else {
            val inline = inlineRegex.matchEntire(own) ?: continue
            val links = el.select("a")
            if (links.isNotEmpty()) found += links.map { it.text() } else found += inline.groupValues[2]
        }
        if (found.size >= 80) break
    }
    return found
}

private fun jsonLdGenres(doc: Document): List<String> {
    val out = ArrayList<String>()
    fun addJson(value: Any?) {
        when (value) {
            is String -> out += value
            is JSONArray -> for (i in 0 until value.length()) addJson(value.opt(i))
            is JSONObject -> addJson(value.opt("name"))
        }
    }
    fun scan(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.equals("genre", true)) addJson(value.opt(key)) else scan(value.opt(key))
                }
            }
            is JSONArray -> for (i in 0 until value.length()) scan(value.opt(i))
        }
    }
    doc.select("script[type=application/ld+json]").forEach { script ->
        val text = script.data().ifBlank { script.html() }
        runCatching { scan(JSONObject(text)) }.recoverCatching { scan(JSONArray(text)) }
    }
    return out
}

/**
 * Genres/tags of an entry page, most trustworthy source first:
 *  1. structured data (JSON-LD, itemprop, article:tag) and the theme's genre containers or a "Genre(s)" label,
 *  2. only when no genres were found: "Tags"/"Categories" blocks and tag link patterns,
 *  3. only as a last resort: meta keywords that do not look like SEO text.
 * Menus, sidebars, "related/popular" blocks and the site's global genre list are never read, so an entry no
 * longer inherits every genre of the navigation bar.
 */
internal fun extractGenreTags(doc: Document): List<String> {
    fun links(selector: String): List<String> =
        doc.select(selector).filterNot(::inPageChrome).map { it.attr("content").ifBlank { it.text() } }

    val genres = ArrayList<String>()
    genres += jsonLdGenres(doc)
    genres += links("meta[property=article:tag], meta[property=book:tag]")
    genres += links(GENRE_CONTAINERS)
    genres += labeled(doc, GENRE_LABEL, INLINE_GENRE_LABEL)
    normalizeGenreList(genres).takeIf { it.isNotEmpty() }?.let { return it }

    val tags = ArrayList<String>()
    tags += links(TAG_CONTAINERS)
    tags += labeled(doc, TAG_LABEL, INLINE_TAG_LABEL)
    normalizeGenreList(tags).takeIf { it.isNotEmpty() }?.let { return it }

    val patterned = links(LINK_PATTERNS)
    if (patterned.size in 1..40) normalizeGenreList(patterned).takeIf { it.isNotEmpty() }?.let { return it }

    val keywords = doc.select("meta[name=keywords]").joinToString(",") { it.attr("content") }
        .split(',').map { it.trim() }
        .filter { it.isNotEmpty() && it.split(' ').size <= 3 && !GENRE_SEO_WORDS.containsMatchIn(it) }
    return if (keywords.size in 1..25) normalizeGenreList(keywords) else emptyList()
}

internal fun parseSiteDate(text: String): Long {
    val t = text.trim()
    if (t.isBlank()) return 0L
    val rel = Regex("""(\d+)\s*(second|min|hour|day|week|month|year)""", RegexOption.IGNORE_CASE).find(t)
    if (rel != null) {
        val n = rel.groupValues[1].toLong()
        val unit = when (rel.groupValues[2].lowercase()) {
            "second" -> 1_000L
            "min" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            "month" -> 2_592_000_000L
            else -> 31_536_000_000L
        }
        return System.currentTimeMillis() - n * unit
    }
    for (p in listOf("MMMM dd, yyyy", "MMM dd, yyyy", "MMM-dd-yyyy", "dd MMMM yyyy", "dd/MM/yyyy", "yyyy-MM-dd")) {
        val d = runCatching { SimpleDateFormat(p, Locale.US).parse(t) }.getOrNull()
        if (d != null) return d.time
    }
    return 0L
}

internal fun statusFromText(text: String): Int {
    val t = text.lowercase().replace('_', ' ').replace('-', ' ')
    return when {
        Regex("""\b(axed|discontinued|cancelled|canceled|dropped)\b""").containsMatchIn(t) -> 4
        Regex("""\b(hiatus|on hold|paused)\b""").containsMatchIn(t) -> 3
        Regex("""\b(ongoing|publishing|updating|releasing)\b""").containsMatchIn(t) -> 1
        Regex("""\b(completed|complete|finished|ended)\b""").containsMatchIn(t) -> 2
        else -> 0
    }
}

/** Key used to decide that two sources are showing the same title. */
fun normalizeTitle(title: String): String =
    title.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")
