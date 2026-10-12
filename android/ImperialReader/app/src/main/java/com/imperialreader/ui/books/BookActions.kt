package com.imperialreader.ui.books

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSource
import com.imperialreader.data.book.EpubBuilder
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.network.await
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.sanitize
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** Shared "what do you want to do with this book" flow used by Browse → Books and Global search. */
object BookActions {

    fun show(activity: AppCompatActivity, item: BookItem) {
        val details = buildString {
            append(item.author ?: "Unknown author")
            append("\nSource: ").append(item.sourceName)
            item.description?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(500)) }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(item.title)
            .setMessage(details)
            .apply {
                val source = BookSources.get(item.sourceId)
                if (source?.isWebNovel == true) {
                    setPositiveButton("Download chapters as EPUBs…") { _, _ -> chooseChapters(activity, item, source) }
                } else {
                    setPositiveButton("Download & add to library") { _, _ -> addToLibrary(activity, item) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseChapters(activity: AppCompatActivity, item: BookItem, source: BookSource) {
        val options = arrayOf("First 25 chapters", "First 100 chapters", "First 300 chapters", "All chapters")
        val counts = intArrayOf(25, 100, 300, Int.MAX_VALUE)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Download “${item.title}”")
            .setItems(options) { _, which -> downloadNovel(activity, item, source, counts[which]) }
            .show()
    }

    /** Fetches chapters one by one, saves one EPUB per chapter under local/, and opens the library entry. */
    private fun downloadNovel(activity: AppCompatActivity, item: BookItem, source: BookSource, limit: Int) {
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(item.title)
            .setMessage("Reading chapter list…")
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()
        job = activity.lifecycleScope.launch {
            var saved = 0
            try {
                val list = source.chapters(item).take(limit)
                if (list.isEmpty()) throw IOException("No chapters found. This site may need a browser to list them.")
                list.forEachIndexed { i, ch ->
                    dialog.setMessage("Downloading chapter ${i + 1} of ${list.size}\n${ch.title}")
                    val html = try {
                        source.chapterHtml(ch).takeIf { it.isNotBlank() }
                            ?: throw IOException("The source returned an empty chapter")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw IOException("Could not download “${ch.title}”: ${e.message}", e)
                    }
                    withContext(Dispatchers.IO) {
                        saveNovelChapter(activity.applicationContext, item, i, ch.title, html)
                    }
                    saved++
                    delay(100)
                }
                dialog.setMessage("Adding chapters to your library…")
                val id = withContext(Dispatchers.IO) {
                    LocalScanner(activity.applicationContext).scan()
                    activity.applicationContext.app.db.mangaDao()
                        .find(LOCAL_SOURCE_ID, item.title.sanitize().take(100))?.id
                        ?: throw IOException("Downloaded, but the library could not import the chapters")
                }
                dialog.dismiss()
                activity.startActivity(MangaDetailsActivity.intent(activity, id))
            } catch (e: CancellationException) {
                if (saved > 0) withContext(NonCancellable + Dispatchers.IO) {
                    LocalScanner(activity.applicationContext).scan()
                }
                dialog.dismiss()
                activity.toast(if (saved > 0) "Download cancelled; $saved chapter(s) saved to your library" else "Download cancelled")
                throw e
            } catch (e: Exception) {
                if (saved > 0) withContext(NonCancellable + Dispatchers.IO) {
                    LocalScanner(activity.applicationContext).scan()
                }
                dialog.dismiss()
                val partial = if (saved > 0) " ($saved chapter(s) saved)" else ""
                activity.toast("Download failed$partial: ${e.message}")
            }
        }
    }

    private fun saveNovelChapter(ctx: Context, item: BookItem, index: Int, title: String, html: String) {
        val app = ctx.app
        val local = app.storage.localDir() ?: throw IOException("Storage is not available")
        val folderName = item.title.sanitize().take(100)
        val dir = app.storage.findOrCreateDir(local, folderName) ?: throw IOException("Cannot create folder")
        val fileName = "%04d - %s.epub".format(index + 1, title.sanitize().take(90))
        dir.findFile(fileName)?.delete()
        val file = dir.createFile("application/epub+zip", fileName)
            ?: throw IOException("Cannot create EPUB for “$title”")
        try {
            val out = ctx.contentResolver.openOutputStream(file.uri, "w")
                ?: throw IOException("Cannot write EPUB for “$title”")
            out.use { EpubBuilder.build(item.title, item.author, listOf(title to html), it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        saveCover(ctx, item, dir)
    }

    private fun saveCover(ctx: Context, item: BookItem, dir: androidx.documentfile.provider.DocumentFile) {
        val cover = item.coverUrl ?: return
        if (dir.findFile("cover.jpg") != null) return
        try {
            ctx.app.network.client.newCall(Request.Builder().url(cover).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val c = dir.createFile("image/jpeg", "cover.jpg")
                    if (c != null) ctx.contentResolver.openOutputStream(c.uri, "w")?.use { resp.body!!.byteStream().copyTo(it) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun addToLibrary(activity: AppCompatActivity, item: BookItem) {
        activity.toast("Downloading “${item.title}”…")
        activity.lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { download(activity.applicationContext, item) }
                activity.startActivity(MangaDetailsActivity.intent(activity, id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast("Download failed: ${e.message}")
            }
        }
    }

    /** Saves the EPUB as local/<Title>/<Title>.epub, rescans, and returns the library entry id. */
    private suspend fun download(ctx: Context, item: BookItem): Long {
        val app = ctx.app
        val local = app.storage.localDir() ?: throw IOException("Storage is not available")
        val folderName = item.title.sanitize().take(100)
        val dir = app.storage.findOrCreateDir(local, folderName) ?: throw IOException("Cannot create folder")

        val url = (BookSources.get(item.sourceId) ?: throw IOException("Unknown catalog")).resolveEpubUrl(item)
        val fileName = "$folderName.epub"
        dir.findFile(fileName)?.delete()
        val file = dir.createFile("application/epub+zip", fileName) ?: throw IOException("Cannot create file")

        app.network.client.await(Request.Builder().url(url).build()).use { resp ->
            if (!resp.isSuccessful) {
                file.delete()
                throw IOException("HTTP ${resp.code}")
            }
            val out = ctx.contentResolver.openOutputStream(file.uri, "w") ?: throw IOException("Cannot write file")
            out.use { resp.body!!.byteStream().copyTo(it) }
        }
        if (file.length() < 1024) {
            file.delete()
            throw IOException("The download was empty")
        }

        saveCover(ctx, item, dir)

        LocalScanner(ctx).scan()
        return app.db.mangaDao().find(LOCAL_SOURCE_ID, folderName)?.id
            ?: throw IOException("Downloaded, but the library could not import it")
    }
}
