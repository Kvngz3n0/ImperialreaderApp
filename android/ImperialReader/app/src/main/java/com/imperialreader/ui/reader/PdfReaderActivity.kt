package com.imperialreader.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.min

class PdfReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHAPTER = "chapter_id"
        private const val EXTRA_URI = "uri"

        fun intent(ctx: Context, chapterId: Long, uri: String): Intent =
            Intent(ctx, PdfReaderActivity::class.java)
                .putExtra(EXTRA_CHAPTER, chapterId)
                .putExtra(EXTRA_URI, uri)
    }

    private lateinit var pageImage: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var pageSeek: SeekBar
    private var renderer: PdfRenderer? = null
    private var chapterId = -1L
    private var pageIndex = 0
    private var renderJob: Job? = null
    private var updatingSeek = false
    private val renderMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)
        pageImage = findViewById(R.id.pdfPage)
        pageLabel = findViewById(R.id.pageLabel)
        pageSeek = findViewById(R.id.pageSeek)
        chapterId = intent.getLongExtra(EXTRA_CHAPTER, -1L)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialToolbar>(R.id.toolbar).title = "PDF reader"
        findViewById<View>(R.id.btnPrevious).setOnClickListener { showPage(pageIndex - 1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { showPage(pageIndex + 1) }
        pageSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updatePageLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!updatingSeek) showPage(seekBar.progress)
            }
        })

        lifecycleScope.launch {
            try {
                val chapter = withContext(Dispatchers.IO) {
                    app.db.chapterDao().getById(chapterId) ?: throw IOException("Chapter not found")
                }
                findViewById<MaterialToolbar>(R.id.toolbar).title = chapter.name
                val uri = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty())
                val opened = withContext(Dispatchers.IO) {
                    val descriptor = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("Cannot open PDF")
                    try {
                        PdfRenderer(descriptor)
                    } catch (e: Exception) {
                        descriptor.close()
                        throw e
                    }
                }
                if (opened.pageCount == 0) {
                    opened.close()
                    throw IOException("PDF contains no pages")
                }
                renderer = opened
                pageSeek.max = opened.pageCount - 1
                val startPage = if (chapter.read) 0 else chapter.lastPageRead
                showPage(startPage.coerceIn(0, opened.pageCount - 1))
                app.repo.recordHistory(chapterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't open PDF: ${e.message}")
                finish()
            }
        }
    }

    private fun showPage(target: Int) {
        val pdf = renderer ?: return
        if (target !in 0 until pdf.pageCount) return
        pageIndex = target
        updatingSeek = true
        pageSeek.progress = target
        updatingSeek = false
        updatePageLabel(target)
        val targetWidth = (pageImage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).coerceAtMost(1800)
        val targetHeight = (pageImage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).coerceAtMost(2400)
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    renderMutex.withLock { render(pdf, target, targetWidth, targetHeight) }
                }
                pageImage.setImageBitmap(bitmap)
                val chapter = app.db.chapterDao().getById(chapterId)
                    ?: throw IOException("Chapter no longer exists")
                app.db.chapterDao().saveProgress(chapterId, target, chapter.read || target == pdf.pageCount - 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't render PDF page: ${e.message}")
            }
        }
    }

    private fun updatePageLabel(page: Int) {
        val pageCount = renderer?.pageCount ?: 0
        pageLabel.text = "Page ${page + 1} / $pageCount"
    }

    private fun render(pdf: PdfRenderer, index: Int, maxWidth: Int, maxHeight: Int): Bitmap {
        val page = pdf.openPage(index)
        try {
            val scale = min(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height).coerceAtMost(1.5f)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        val opened = renderer
        renderer = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            renderMutex.withLock { opened?.close() }
        }
        super.onDestroy()
    }
}
