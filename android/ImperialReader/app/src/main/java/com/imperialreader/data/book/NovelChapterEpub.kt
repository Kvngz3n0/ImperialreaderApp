package com.imperialreader.data.book

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.util.app
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

object NovelChapterEpub {

    suspend fun download(ctx: Context, manga: MangaEntity, chapter: ChapterEntity) {
        val source = BookSources.get(manga.source)?.takeIf { it.isWebNovel }
            ?: throw IOException("Web-novel source is unavailable")
        val html = source.chapterHtml(NovelChapter(chapter.name, chapter.url))
            .takeIf { it.isNotBlank() } ?: throw IOException("The source returned an empty chapter")
        val dir = ctx.app.storage.chapterDir(manga, chapter, create = true)
            ?: throw IOException("Cannot create chapter download folder")
        dir.listFiles().forEach { file ->
            if (!file.delete()) throw IOException("Could not replace the existing chapter download")
        }
        val file = dir.createFile("application/epub+zip", "chapter.epub")
            ?: throw IOException("Cannot create EPUB for “${chapter.name}”")
        try {
            val output = ctx.contentResolver.openOutputStream(file.uri, "w")
                ?: throw IOException("Cannot write EPUB for “${chapter.name}”")
            output.use { EpubBuilder.build(manga.title, manga.author, listOf(chapter.name to html), it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    suspend fun onlineUri(ctx: Context, manga: MangaEntity, chapter: ChapterEntity): String {
        val source = BookSources.get(manga.source)?.takeIf { it.isWebNovel }
            ?: throw IOException("Web-novel source is unavailable")
        val html = source.chapterHtml(NovelChapter(chapter.name, chapter.url))
            .takeIf { it.isNotBlank() } ?: throw IOException("The source returned an empty chapter")
        val cache = File(ctx.cacheDir, "online-epub").apply { mkdirs() }
        val epub = File.createTempFile("chapter-${chapter.id}-", ".epub", cache)
        try {
            val output = ByteArrayOutputStream()
            EpubBuilder.build(manga.title, manga.author, listOf(chapter.name to html), output)
            epub.writeBytes(output.toByteArray())
            return android.net.Uri.fromFile(epub).toString()
        } catch (e: Exception) {
            epub.delete()
            throw e
        }
    }

    fun downloadedFile(ctx: Context, manga: MangaEntity, chapter: ChapterEntity): DocumentFile? =
        ctx.app.storage.chapterDir(manga, chapter, create = false)
            ?.listFiles()
            ?.firstOrNull { !it.isDirectory && it.name?.endsWith(".epub", ignoreCase = true) == true }
}
