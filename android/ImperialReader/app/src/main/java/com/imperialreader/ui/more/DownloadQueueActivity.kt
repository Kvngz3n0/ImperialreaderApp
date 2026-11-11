package com.imperialreader.ui.more

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.download.DownloadQueueItem
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.ui.theme.ThemedAppCompatActivity
import com.imperialreader.util.app
import kotlinx.coroutines.launch

class DownloadQueueActivity : ThemedAppCompatActivity() {
    private lateinit var summary: TextView
    private lateinit var pauseAll: Button
    private lateinit var empty: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DownloadQueueAdapter
    private var queueItems: List<DownloadQueueItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_queue)
        findViewById<MaterialToolbar>(R.id.downloadQueueToolbar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        summary = findViewById(R.id.downloadQueueSummary)
        pauseAll = findViewById(R.id.downloadQueuePauseAll)
        empty = findViewById(R.id.downloadQueueEmpty)
        recycler = findViewById(R.id.downloadQueueList)
        adapter = DownloadQueueAdapter(
            onClick = { item -> item.mangaId?.let { startActivity(MangaDetailsActivity.intent(this, it)) } },
            onActions = ::showDownloadActions,
            onPauseAllForEntry = { mangaId, allPaused ->
                if (allPaused) app.downloads.resumeManga(mangaId) else app.downloads.pauseManga(mangaId)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        pauseAll.setOnClickListener {
            val activeItems = queueItems.filter { it.chapterId != null && !it.failed }
            if (activeItems.isNotEmpty() && activeItems.all { it.paused }) app.downloads.resumeAll()
            else app.downloads.pauseAll()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.downloads.queueItems.collect(::renderQueue)
            }
        }
    }

    private fun renderQueue(items: List<DownloadQueueItem>) {
        queueItems = items
        val chapters = items.filter { it.chapterId != null }
        val active = chapters.count { it.downloading && !it.paused }
        val waiting = chapters.count { !it.downloading && !it.failed }
        val paused = chapters.count { it.paused }
        val failed = chapters.count { it.failed }
        summary.text = buildList {
            if (active > 0) add("$active downloading")
            if (waiting > 0) add("$waiting queued")
            if (paused > 0) add("$paused paused")
            if (failed > 0) add("$failed failed")
        }.joinToString(" · ").ifBlank { "Nothing is queued" }
        val actionable = chapters.filterNot { it.failed }
        pauseAll.visibility = if (actionable.isEmpty()) View.GONE else View.VISIBLE
        pauseAll.text = if (actionable.isNotEmpty() && actionable.all { it.paused }) "Resume all" else "Pause all"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitQueue(items)
    }

    private fun showDownloadActions(item: DownloadQueueItem) {
        val mangaId = item.mangaId ?: return
        val chapterId = item.chapterId ?: return
        val actions = if (item.failed) {
            listOf("Retry from start", "Remove failed download", "Cancel all downloads from this entry")
        } else {
            listOf(if (item.paused) "Resume" else "Pause", "Cancel chapter", "Cancel all downloads from this entry")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(item.chapterName)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (item.failed) app.downloads.retryChapter(chapterId)
                    else if (item.paused) app.downloads.resumeChapter(chapterId)
                    else app.downloads.pauseChapter(chapterId)
                    1 -> app.downloads.cancelChapter(chapterId)
                    2 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Cancel all downloads from ${item.mangaTitle}?")
                        .setMessage("All queued, active, and failed chapter downloads for this entry will be removed.")
                        .setNegativeButton("Keep downloads", null)
                        .setPositiveButton("Cancel all") { _, _ -> app.downloads.cancelAllForManga(mangaId) }
                        .show()
                }
            }
            .show()
    }
}