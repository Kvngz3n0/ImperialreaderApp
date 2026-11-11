package com.imperialreader.ui.library

/**
 * Library search. Plain words must each match the title, author, artist, genre/tag or source name (so "mangadex",
 * "isekai" and an author's name all work in the one search box). Prefixes narrow a term to one field:
 *
 *   source:mangadex   ext:mangadex   extension:"Asura Scans"   (the source / extension name or id)
 *   author:oda        artist:"Kishimoto"
 *   genre:romance     tag:isekai
 *   title:"one piece"
 *
 * Put a minus in front of a prefixed term to exclude it: -genre:ecchi, -source:nhentai.
 */
internal class LibraryQuery private constructor(private val terms: List<Term>) {
    private data class Term(val field: String?, val value: String, val negated: Boolean)

    val isBlank: Boolean get() = terms.isEmpty()

    fun matches(
        title: String,
        author: String?,
        artist: String?,
        genre: String?,
        sourceName: String?,
        sourceId: String?
    ): Boolean {
        val genres = genre.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        fun has(field: String?, value: String): Boolean {
            fun inText(text: String?) = text?.contains(value, ignoreCase = true) == true
            return when (field) {
                "title" -> inText(title)
                "author" -> inText(author)
                "artist" -> inText(artist)
                "genre" -> genres.any { it.contains(value, ignoreCase = true) }
                "source" -> inText(sourceName) || inText(sourceId)
                else -> inText(title) || inText(author) || inText(artist) ||
                    genres.any { it.contains(value, ignoreCase = true) } || inText(sourceName)
            }
        }
        return terms.all { term -> has(term.field, term.value) != term.negated }
    }

    companion object {
        private val token = Regex("""(-?)(?:([A-Za-z]+):)?("([^"]*)"|\S+)""")
        private val aliases = mapOf(
            "source" to "source", "src" to "source", "ext" to "source", "extension" to "source",
            "author" to "author", "writer" to "author",
            "artist" to "artist",
            "genre" to "genre", "tag" to "genre", "tags" to "genre", "genres" to "genre",
            "title" to "title"
        )

        fun parse(raw: String): LibraryQuery {
            val terms = ArrayList<Term>()
            for (m in token.findAll(raw)) {
                val minus = m.groupValues[1] == "-"
                val prefix = m.groupValues[2].lowercase()
                val quoted = m.groupValues[4]
                val body = if (m.groupValues[3].startsWith("\"")) quoted else m.groupValues[3]
                val field = aliases[prefix]
                when {
                    prefix.isNotEmpty() && field == null -> {
                        // not a known prefix ("re:zero"): treat the whole thing as a plain word
                        val whole = (prefix + ":" + body).trim()
                        if (whole.isNotBlank()) terms += Term(null, whole, false)
                    }
                    body.isBlank() -> Unit
                    else -> terms += Term(field, body.trim(), minus && field != null)
                }
            }
            return LibraryQuery(terms)
        }
    }
}
