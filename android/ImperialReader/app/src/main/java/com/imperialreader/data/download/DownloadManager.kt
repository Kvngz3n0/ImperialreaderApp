package com.imperialreader.data.download

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import com.imperialreader.util.app
import com.imperialreader.util.mimeForExt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sequential chapter download queue (3 pages in parallel per chapter).
 * Progress is exposed per chapter id (0..100). A chapter id that is present is queued or downloading.
 * The queue lives in memory; chapters that finished are flagged `downloaded` in the database.
 */
class DownloadManager(private val ctx: Context) {

    private data class Task(val manga: MangaEntity, val chapter: ChapterEntity)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Task>()
    private var worker: Job? = null

    private val _progress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val progress: StateFlow<Map<Long, Int>> = _progress

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors

    private fun setProgress(id: Long, value: Int?) {
        _progress.value = if (value == null) _progress.value - id else _progress.value + (id to value)
    }

    @Synchronized
    fun enqueue(manga: MangaEntity, chapters: List<ChapterEntity>) {
        for (c in chapters) {
            if (c.downloaded || _progress.value.containsKey(c.id)) continue
            queue.addLast(Task(manga, c))
            setProgress(c.id, 0)
        }
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (worker?.isActive == true) return
        DownloadService.start(ctx)
        worker = scope.launch {
            try {
                while (true) {
                    val task = synchronized(this@DownloadManager) { queue.removeFirstOrNull() } ?: break
                    try {
                        download(task)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ctx.app.storage.deleteDownload(task.manga, task.chapter)
                        _errors.tryEmit("Download failed: ${task.chapter.name} (${e.message})")
                    } finally {
                        setProgress(task.chapter.id, null)
                    }
                }
            } finally {
                DownloadService.stop(ctx)
            }
        }
    }

    private suspend fun download(task: Task) {
        val app = ctx.app
        val source = app.sources.get(task.manga.source) ?: throw IOException("Source not available")
        val loaded = source.loadPages(
            SManga(url = task.manga.url, title = task.manga.title),
            SChapter(url = task.chapter.url, name = task.chapter.name)
        )
        val pages = loaded.urls
        if (pages.isEmpty()) throw IOException("No pages found")
        val dir = app.storage.chapterDir(task.manga, task.chapter, create = true)
            ?: throw IOException("Cannot create download folder")
        dir.listFiles().forEach { it.delete() }

        val done = AtomicInteger(0)
        val sem = Semaphore(3)
        coroutineScope {
            pages.mapIndexed { index, url ->
                async {
                    sem.withPermit {
                        downloadPage(loaded.headers, url, index, dir)
                        setProgress(task.chapter.id, done.incrementAndGet() * 100 / pages.size)
                    }
                }
            }.awaitAll()
        }
        app.db.chapterDao().setDownloaded(task.chapter.id, true)
    }

    private suspend fun downloadPage(headers: Map<String, String>, url: String, index: Int, dir: DocumentFile) {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            var created: DocumentFile? = null
            try {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                ctx.app.network.client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val ct = resp.header("Content-Type").orEmpty()
                    val ext = when {
                        "png" in ct -> "png"
                        "webp" in ct -> "webp"
                        "gif" in ct -> "gif"
                        "avif" in ct -> "avif"
                        else -> "jpg"
                    }
                    val file = dir.createFile(mimeForExt(ext), "%03d.%s".format(index + 1, ext))
                        ?: throw IOException("Cannot create file")
                    created = file
                    val out = ctx.contentResolver.openOutputStream(file.uri, "w")
                        ?: throw IOException("Cannot open file for writing")
                    out.use { resp.body!!.byteStream().copyTo(it) }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                created?.delete()
                delay(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    suspend fun deleteDownload(manga: MangaEntity, chapter: ChapterEntity) {
        ctx.app.storage.deleteDownload(manga, chapter)
        ctx.app.db.chapterDao().setDownloaded(chapter.id, false)
    }
}
