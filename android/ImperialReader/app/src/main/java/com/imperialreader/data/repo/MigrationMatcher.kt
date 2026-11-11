package com.imperialreader.data.repo

import java.text.Normalizer

/** Title matching used by mass migration's "Auto-match all". Pure functions so they can be unit-tested. */
object MigrationMatcher {
    /** At or above this a match is accepted immediately and the remaining destination sources are not searched. */
    const val CONFIDENT = 0.92

    /** Below this a candidate is never auto-accepted; the entry is left for manual review. */
    const val ACCEPT = 0.72

    private val stopWords = setOf("the", "a", "an", "of", "and", "to", "in")
    private val bracketed = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val nonWord = Regex("""[^\p{L}\p{N}\s]""")

    fun normalize(title: String): String {
        val stripped = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        return stripped.lowercase()
            .replace(bracketed, " ")
            .replace("&", " and ")
            .replace(nonWord, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** 0.0 (unrelated) .. 1.0 (same title once case, punctuation and "(Official)"-style tags are ignored). */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ta = na.split(' ').filter { it !in stopWords }.toSet()
        val tb = nb.split(' ').filter { it !in stopWords }.toSet()
        val jaccard = if (ta.isEmpty() || tb.isEmpty()) 0.0 else ta.intersect(tb).size.toDouble() / ta.union(tb).size
        val edit = 1.0 - levenshtein(na, nb).toDouble() / maxOf(na.length, nb.length)
        return maxOf(jaccard, edit)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
