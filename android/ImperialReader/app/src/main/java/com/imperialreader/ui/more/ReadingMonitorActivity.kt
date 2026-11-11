package com.imperialreader.ui.more

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.db.ReadingSessionEntity
import com.imperialreader.data.repo.ReadingMonitor
import com.imperialreader.data.repo.ReadingMonitorStats
import com.imperialreader.data.repo.ReadingRange
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReadingMonitorActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {
    private lateinit var rangeSpinner: Spinner
    private lateinit var content: LinearLayout
    private lateinit var chart: DailyReadingChart
    private var selectedRange = ReadingRange.DAYS_7
    private var exportSessions: List<ReadingSessionEntity> = emptyList()
    private var mangaById: Map<Long, MangaEntity> = emptyMap()

    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val csv = ReadingMonitor.csv(exportSessions, mangaById)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                    ?: throw IllegalStateException("Could not open the selected file")
                toast("Reading statistics exported")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("CSV export failed: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this@ReadingMonitorActivity, android.R.attr.colorBackground, Color.BLACK))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Reading monitor"
            setNavigationIcon(com.imperialreader.R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        rangeSpinner = Spinner(this)
        rangeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ReadingRange.entries.map { it.label }
        )
        rangeSpinner.setSelection(0)
        root.addView(rangeSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply {
            setMargins(dp(16), dp(8), dp(16), dp(4))
        })

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        rangeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedRange = ReadingRange.entries[position]
                loadStats()
            }
        }
        loadStats()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            try {
                val dao = app.db.readingSessionDao()
                val sessions = dao.getAll()
                val mangas = dao.getAllManga()
                val completed = dao.getCompletedChapters()
                val sources = app.sources.all().associate { it.id to it.name }
                val animeSources = app.sources.all().filter { it.isAnime }.map { it.id }.toSet()
                val stats = ReadingMonitor.calculate(
                    sessions = sessions,
                    mangas = mangas,
                    completedChapters = completed,
                    animeSourceIds = animeSources,
                    sourceNames = sources,
                    range = selectedRange,
                    now = System.currentTimeMillis()
                )
                exportSessions = sessions.filter {
                    it.durationMs > 0L && it.startedAt >= ReadingMonitor.rangeStart(selectedRange, System.currentTimeMillis())
                }
                mangaById = mangas.associateBy { it.id }
                render(stats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not load reading statistics: ${e.message}")
            }
        }
    }

    private fun render(stats: ReadingMonitorStats) {
        content.removeAllViews()
        val note = text("Reading time is tracked from now on. Completion counts include existing read flags.")
        note.textSize = 13f
        note.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        content.addView(note)

        val statsText = buildString {
            appendLine("Anime watched: ${ReadingMonitor.formatDuration(stats.watchedMs)}")
            appendLine("Time read: ${ReadingMonitor.formatDuration(stats.readMs)}")
            appendLine("Completed titles: ${stats.titlesCompleted}")
            appendLine("Chapters read: ${stats.chaptersRead}")
            appendLine("Episodes watched: ${stats.episodesWatched}")
            appendLine("Pages read: ${stats.pagesRead}")
            appendLine("Reading streak: ${stats.streakDays} days")
            appendLine("Average per day: ${ReadingMonitor.formatDuration(stats.averagePerDayMs)}")
            append("Longest session: ${ReadingMonitor.formatDuration(stats.longestSessionMs)}")
        }
        content.addView(text(statsText).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, dp(16))
        })

        content.addView(sectionTitle("Daily activity · last 30 days"))
        chart = DailyReadingChart().apply {
            values = (0..29).map { offset ->
                val day = LocalDate.now().minusDays((29 - offset).toLong())
                day to (stats.dailyMs[day] ?: 0L)
            }
        }
        content.addView(chart, LinearLayout.LayoutParams(-1, dp(150)))
        content.addView(sectionTitle("Top titles by time"))
        content.addView(text(stats.topTitles.withIndex().joinToString("\n") { (i, row) ->
            "${i + 1}. ${row.first} — ${ReadingMonitor.formatDuration(row.second)}"
        }.ifBlank { "No tracked time yet" }))
        content.addView(sectionTitle("Top genres by time"))
        content.addView(text(stats.topGenres.joinToString("\n") {
            "${it.first} — ${ReadingMonitor.formatDuration(it.second)}"
        }.ifBlank { "No tracked genre time yet" }))
        content.addView(sectionTitle("By media type"))
        content.addView(text(stats.byMediaKind.entries.sortedByDescending { it.value }.joinToString("\n") {
            "${it.key.lowercase().replaceFirstChar(Char::uppercase)} — ${ReadingMonitor.formatDuration(it.value)}"
        }.ifBlank { "No tracked time yet" }))
        content.addView(sectionTitle("By source"))
        content.addView(text(stats.bySource.entries.sortedByDescending { it.value }.joinToString("\n") {
            "${it.key} — ${ReadingMonitor.formatDuration(it.value)}"
        }.ifBlank { "No tracked time yet" }))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(Button(this).apply {
            text = "Export CSV"
            setOnClickListener { createCsv.launch("reading-statistics.csv") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "Reset statistics"
            setOnClickListener { confirmReset() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(buttons)
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset statistics?")
            .setMessage("This deletes tracked reading time. Existing chapter read flags are preserved and continue to supply completion counts.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.db.readingSessionDao().clearAll()
                        loadStats()
                        toast("Tracked reading statistics reset")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast("Could not reset statistics: ${e.message}")
                    }
                }
            }
            .show()
    }

    private fun sectionTitle(label: String) = text(label).apply {
        textSize = 18f
        setPadding(0, dp(16), 0, dp(6))
        setTextColor(MaterialColors.getColor(this@ReadingMonitorActivity, com.google.android.material.R.attr.colorPrimary, Color.BLUE))
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class DailyReadingChart : View(this@ReadingMonitorActivity) {
        var values: List<Pair<LocalDate, Long>> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (values.isEmpty()) return
            val left = dp(8).toFloat()
            val right = width - dp(8).toFloat()
            val top = dp(8).toFloat()
            val bottom = height - dp(24).toFloat()
            val maxValue = values.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
            val gap = dp(2).toFloat()
            val barWidth = ((right - left) / values.size - gap).coerceAtLeast(1f)
            paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
            values.forEachIndexed { index, (_, value) ->
                val barHeight = (bottom - top) * value.toFloat() / maxValue
                val x = left + index * (barWidth + gap)
                canvas.drawRect(x, bottom - barHeight, x + barWidth, bottom, paint)
            }
            paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
            paint.textSize = dp(10).toFloat()
            val formatter = DateTimeFormatter.ofPattern("d", Locale.getDefault())
            values.forEachIndexed { index, (date, _) ->
                if (index % 5 == 0 || index == values.lastIndex) {
                    val x = left + index * (barWidth + gap)
                    canvas.drawText(date.format(formatter), x, height - dp(6).toFloat(), paint)
                }
            }
        }
    }
}
