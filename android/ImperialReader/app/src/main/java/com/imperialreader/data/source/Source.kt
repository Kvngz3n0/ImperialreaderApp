package com.imperialreader.data.source

data class SManga(
    var url: String,
    var title: String,
    var thumbnailUrl: String? = null,
    var author: String? = null,
    var artist: String? = null,
    var description: String? = null,
    var genre: String? = null,
    /** 0 unknown, 1 ongoing, 2 completed, 3 hiatus/cancelled */
    var status: Int = 0
)

data class SChapter(
    var url: String,
    var name: String,
    var dateUpload: Long = 0L,
    var chapterNumber: Float = -1f,
    var scanlator: String? = null
)

data class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean)

/** Page image URLs plus the headers needed to load them (they can differ per chapter for combined titles). */
data class ChapterPages(val urls: List<String>, val headers: Map<String, String>)

/** Contract every built-in or custom source implements. */
interface Source {
    val id: String
    val name: String
    val lang: String
    val baseUrl: String

    /** Adult (18+) site: hidden unless "Show 18+" is enabled in Settings. */
    val nsfw: Boolean get() = false

    suspend fun getPopular(page: Int): MangasPage
    suspend fun getLatest(page: Int): MangasPage
    suspend fun search(query: String, page: Int): MangasPage
    suspend fun getMangaDetails(manga: SManga): SManga
    suspend fun getChapters(manga: SManga): List<SChapter>
    suspend fun getPages(chapter: SChapter): List<String>

    /** Same as [getPages] but also returns the image headers. Combined sources override this. */
    suspend fun loadPages(manga: SManga, chapter: SChapter): ChapterPages =
        ChapterPages(getPages(chapter), imageHeaders())

    /** Headers needed when loading page images (usually a Referer). */
    fun imageHeaders(): Map<String, String>
}
