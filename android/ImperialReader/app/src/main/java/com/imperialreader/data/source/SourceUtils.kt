package com.imperialreader.data.source

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
    val t = text.lowercase()
    return when {
        "ongoing" in t || "publishing" in t || "updating" in t -> 1
        "complete" in t || "finished" in t || "ended" in t -> 2
        "hiatus" in t || "cancel" in t || "dropped" in t -> 3
        else -> 0
    }
}

/** Key used to decide that two sources are showing the same title. */
fun normalizeTitle(title: String): String =
    title.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")
