package com.imperialreader.ui.details

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSources
import com.imperialreader.ui.browse.searchSourceEntries
import com.imperialreader.ui.browse.sourceCategoryName
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.repo.BookMigrationManager
import com.imperialreader.data.repo.MangaMigrationManager
import com.imperialreader.data.repo.MangaMigrationOptions
import com.imperialreader.data.repo.MigrationMatcher
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import com.imperialreader.ui.books.BookActions
import com.imperialreader.ui.common.loadCover
import com.imperialreader.util.app
import com.imperialreader.util.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class MassMigrationActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {
    private enum class Kind { MANGA, WEB_NOVEL, EPUB }

    private sealed interface Candidate {
        val title: String
        val cover: String?
        data class Manga(val source: Source, val manga: SManga) : Candidate {
            override val title get() = manga.title
            override val cover get() = manga.thumbnailUrl
        }
        data class Book(val item: BookItem) : Candidate {
            override val title get() = item.title
            override val cover get() = item.coverUrl
        }
    }

    private data class QueueEntry(
        val original: MangaEntity,
        val kind: Kind,
        val sources: List<Pair<String, String>>,
        var sourceId: String? = null,
        var candidate: Candidate? = null,
        var status: String = "Choose a destination source",
        var active: Boolean = true,
        var statusView: TextView? = null,
        var candidateView: TextView? = null,
        var chapterReviewView: TextView? = null,
        var chapterReviewButton: Button? = null,
        var chapterComparison: ChapterComparison? = null
    )

    companion object {
        private const val EXTRA_IDS = "mass_migration_ids"

        fun intent(context: android.content.Context, ids: List<Long>) =
            android.content.Intent(context, MassMigrationActivity::class.java)
                .putExtra(EXTRA_IDS, ids.toLongArray())
    }

    private val entries = mutableListOf<QueueEntry>()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var progress: ProgressBar
    private lateinit var migrateButton: Button
    private var running = false
    private var reviewingChapters = false

    // "Apply to all": one destination choice for every entry, then automatic matching
    private var bulkSourceIds: List<String> = emptyList()
    private lateinit var bulkSummary: TextView
    private lateinit var autoButton: Button
    private lateinit var reviewChaptersButton: Button
    private var autoJob: Job? = null

    private data class AutoPick(val sourceId: String, val candidate: Candidate, val score: Double)

    private data class ChapterComparison(
        val currentSource: String,
        val destinationSource: String,
        val currentChapters: List<String>,
        val destinationChapters: List<String>,
        val common: List<Pair<String, String>>,
        val currentOnly: List<String>,
        val destinationOnly: List<String>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
            val dao = app.db.mangaDao()
            ids.distinct().forEach { id ->
                val manga = dao.getById(id) ?: return@forEach
                val bookSource = BookSources.get(manga.source)
                val kind = when {
                    bookSource?.isWebNovel == true -> Kind.WEB_NOVEL
                    bookSource != null -> Kind.EPUB
                    manga.source == LOCAL_SOURCE_ID &&
                        manga.url.substringBefore('?').endsWith(".epub", ignoreCase = true) -> Kind.EPUB
                    else -> Kind.MANGA
                }
                val sources = when (kind) {
                    Kind.MANGA -> app.sources.enabled().filter { it.id != manga.source }
                        .map { it.id to sourceCategoryName(it.id, it.isAnime, it.name) }
                    Kind.WEB_NOVEL -> BookSources.all.filter { it.isWebNovel && it.id != manga.source }
                        .map { it.id to it.name }
                    Kind.EPUB -> BookSources.all.filter { !it.isWebNovel && it.inGlobalSearch }
                        .map { it.id to it.name }
                }
                entries += QueueEntry(manga, kind, sources)
            }
            if (entries.isEmpty()) {
                finish()
                return@launch
            }
            buildScreen()
        }
    }

    private fun buildScreen() {
        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Mass migration"
            textSize = 22f
        })
        count = TextView(this).apply { textSize = 14f }
        root.addView(count)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = entries.size
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        root.addView(buildBulkPanel(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        entries.forEach(::addEntryView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        migrateButton = Button(this).apply {
            text = "Migrate matched"
            isEnabled = false
            setOnClickListener { processMigrations() }
        }
        actions.addView(migrateButton)
        root.addView(actions)
        setContentView(root)
        updateCount()
    }

    // ------------------------------------------------------------ apply to all

    private fun buildBulkPanel(): View {
        val allSources = entries.flatMap { it.sources }.distinctBy { it.first }
        val saved = app.prefs.migrationSourceIds
        bulkSourceIds = allSources.map { it.first }.filter { it in saved }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MassMigrationActivity, com.google.android.material.R.attr.colorSurfaceVariant))
                cornerRadius = dp(12).toFloat()
            }
        }
        panel.addView(TextView(this).apply {
            text = "Apply to all entries"
            textSize = 15f
        })
        bulkSummary = TextView(this).apply { textSize = 12f }
        panel.addView(bulkSummary)
        val row = LinearLayout(this).apply { gravity = Gravity.END }
        row.addView(Button(this).apply {
            text = "Choose destinations"
            setOnClickListener { if (!running && autoJob == null) showBulkSourceDialog(allSources) }
        })
        autoButton = Button(this).apply {
            text = "Auto-match all"
            setOnClickListener { if (autoJob != null) stopAutoMatch() else autoMatchAll() }
        }
        row.addView(autoButton)
        panel.addView(row)
        reviewChaptersButton = Button(this).apply {
            text = "Compare chapter lists"
            isEnabled = false
            setOnClickListener { compareAllChapterLists() }
        }
        panel.addView(reviewChaptersButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        updateBulkSummary()
        return panel
    }

    private fun updateBulkSummary() {
        bulkSummary.text = if (bulkSourceIds.isEmpty()) {
            "Choose destinations, auto-match titles, then compare chapter lists before migrating. Each match stays available for review."
        } else {
            "${bulkSourceIds.size} destination source(s) chosen. Auto-matches are suggestions; compare their chapter lists and review each title before migrating."
        }
    }

    private fun showBulkSourceDialog(all: List<Pair<String, String>>) {
        val checked = BooleanArray(all.size) { all[it].first in bulkSourceIds }
        MaterialAlertDialogBuilder(this)
            .setTitle("Destination sources for all entries")
            .setMultiChoiceItems(all.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ -> applyBulkSources(all.filterIndexed { i, _ -> checked[i] }.map { it.first }) }
            .setNeutralButton("Select all") { _, _ -> applyBulkSources(all.map { it.first }) }
            .setNegativeButton("Clear") { _, _ -> applyBulkSources(emptyList()) }
            .show()
    }

    private fun applyBulkSources(ids: List<String>) {
        bulkSourceIds = ids
        app.prefs.migrationSourceIds = ids.toSet()
        updateBulkSummary()
    }

    private fun stopAutoMatch() {
        autoJob?.cancel()
    }

    private fun autoMatchAll() {
        if (running || autoJob != null || reviewingChapters) return
        if (bulkSourceIds.isEmpty()) {
            Toast.makeText(this, "Choose at least one destination source first", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = entries.filter { it.active && it.candidate == null }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Every entry already has a match", Toast.LENGTH_SHORT).show()
            return
        }
        autoButton.text = "Stop"
        migrateButton.isEnabled = false
        progress.max = targets.size
        progress.progress = 0
        progress.visibility = View.VISIBLE
        val job = lifecycleScope.launch {
            var matched = 0
            try {
                targets.forEachIndexed { index, entry ->
                    ensureActive()
                    val ids = bulkSourceIds.filter { id -> entry.sources.any { it.first == id } }
                    if (ids.isEmpty()) {
                        entry.status = "None of the chosen destinations fit this entry · pick one manually"
                    } else {
                        entry.status = "Auto-matching…"
                        updateEntry(entry)
                        val pick = autoMatch(entry, ids)
                        if (pick != null) {
                            entry.sourceId = pick.sourceId
                            entry.candidate = pick.candidate
                            entry.status = "Auto-match suggestion · ${(pick.score * 100).toInt()}% title similarity · review title and chapters"
                            matched++
                        } else {
                            entry.status = "No confident match · use Find matches"
                        }
                    }
                    progress.progress = index + 1
                    updateEntry(entry)
                    updateCount()
                }
            } finally {
                autoJob = null
                autoButton.text = "Auto-match all"
                progress.visibility = View.GONE
                updateCount()
                Toast.makeText(this@MassMigrationActivity, "Auto-matched $matched of ${targets.size}", Toast.LENGTH_LONG).show()
            }
        }
        // the coroutine can finish before this line runs (Main.immediate), so never keep a completed job
        autoJob = if (job.isCompleted) null else job
    }

    private suspend fun autoMatch(entry: QueueEntry, sourceIds: List<String>): AutoPick? {
        var best: AutoPick? = null
        for (sourceId in sourceIds) {
            val candidates = try {
                candidatesFor(entry, sourceId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            for (candidate in candidates) {
                val authorBonus = (candidate as? Candidate.Book)?.item?.author
                    ?.takeIf { it.isNotBlank() && it.equals(entry.original.author, ignoreCase = true) }?.let { 0.1 } ?: 0.0
                val score = minOf(1.0, MigrationMatcher.similarity(entry.original.title, candidate.title) + authorBonus)
                if (best == null || score > best.score) best = AutoPick(sourceId, candidate, score)
            }
            if ((best?.score ?: 0.0) >= MigrationMatcher.CONFIDENT) break
        }
        return best?.takeIf { it.score >= MigrationMatcher.ACCEPT }
    }

    private suspend fun candidatesFor(entry: QueueEntry, sourceId: String): List<Candidate> {
        return when (entry.kind) {
            Kind.MANGA -> {
                val source = app.sources.get(sourceId) ?: return emptyList()
                searchSourceEntries(source, entry.original.title, 1).mangas.take(20).map { Candidate.Manga(source, it) }
            }
            Kind.WEB_NOVEL, Kind.EPUB -> {
                val source = BookSources.get(sourceId) ?: return emptyList()
                source.search(entry.original.title, 1).take(20).map { Candidate.Book(it) }
            }
        }
    }

    private fun addEntryView(entry: QueueEntry) {
        val pad = dp(10)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(com.imperialreader.ui.theme.AppThemeManager.color(this@MassMigrationActivity, com.google.android.material.R.attr.colorSurfaceVariant))
                cornerRadius = dp(12).toFloat()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Cover for ${entry.original.title}"
            loadCover(
                entry.original.displayCoverUrl,
                app.sources.get(entry.original.source)?.imageHeaders() ?: emptyMap()
            )
        }
        header.addView(cover, LinearLayout.LayoutParams(dp(54), dp(76)))
        header.addView(LinearLayout(this@MassMigrationActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(TextView(this@MassMigrationActivity).apply {
                text = entry.original.title
                textSize = 15f
            })
            addView(TextView(this@MassMigrationActivity).apply {
                text = when (entry.kind) {
                    Kind.MANGA -> "Manga"
                    Kind.WEB_NOVEL -> "Web novel"
                    Kind.EPUB -> "EPUB / PDF"
                }
                textSize = 12f
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                if (!running) {
                    entry.active = false
                    card.visibility = View.GONE
                    updateCount()
                }
            }
        })
        card.addView(header)

        val sourceLabels = entry.sources.map { "${it.second} · ${it.first}" }
        val sourceInput = AutoCompleteTextView(this).apply {
            hint = "Search destination sources"
            threshold = 0
            setAdapter(ArrayAdapter(this@MassMigrationActivity, android.R.layout.simple_dropdown_item_1line, sourceLabels))
            setOnItemClickListener { _, _, _, _ ->
                if (running || autoJob != null || reviewingChapters) return@setOnItemClickListener
                entry.sourceId = entry.sources.firstOrNull {
                    "${it.second} · ${it.first}" == text.toString()
                }?.first
                entry.candidate = null
                entry.status = "Source selected · search and review a match"
                updateEntry(entry)
                updateCount()
            }
        }
        card.addView(sourceInput)
        val selectedSource = TextView(this).apply { textSize = 12f }
        entry.candidateView = selectedSource
        card.addView(selectedSource)
        val chapterReview = TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
        }
        entry.chapterReviewView = chapterReview
        card.addView(chapterReview)
        val status = TextView(this).apply { textSize = 12f }
        entry.statusView = status
        card.addView(status)
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(Button(this).apply {
            text = "Find matches"
            isEnabled = entry.sources.isNotEmpty()
            setOnClickListener {
                val sourceId = entry.sourceId
                if (sourceId == null) {
                    sourceInput.showDropDown()
                    entry.status = "Select a compatible source first"
                    updateEntry(entry)
                } else {
                    findMatches(entry, sourceId)
                }
            }
        })
        entry.chapterReviewButton = Button(this).apply {
            text = "Review chapters"
            visibility = View.GONE
            setOnClickListener {
                val comparison = entry.chapterComparison
                if (comparison != null) {
                    showChapterComparison(entry, comparison)
                } else {
                    compareChapterLists(entry)
                }
            }
        }
        buttons.addView(entry.chapterReviewButton)
        card.addView(buttons)
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        updateEntry(entry)
    }

    private fun findMatches(entry: QueueEntry, sourceId: String) {
        if (running || autoJob != null || reviewingChapters) return
        lifecycleScope.launch {
            entry.status = "Searching…"
            updateEntry(entry)
            try {
                val candidates = when (entry.kind) {
                    Kind.MANGA -> {
                        val source = app.sources.get(sourceId)
                            ?: throw IllegalStateException("Destination source is unavailable")
                        source.search(entry.original.title, 1).mangas.take(20)
                            .map { Candidate.Manga(source, it) }
                    }
                    Kind.WEB_NOVEL, Kind.EPUB -> {
                        val source = BookSources.get(sourceId)
                            ?: throw IllegalStateException("Destination catalog is unavailable")
                        val matches = source.search(entry.original.title, 1).take(20)
                        matches.map { Candidate.Book(it) }
                    }
                }
                if (candidates.isEmpty()) {
                    entry.candidate = null
                    entry.status = "No matches · needs manual migration"
                } else {
                    val labels = candidates.map { candidate ->
                        "${candidate.title}${(candidate as? Candidate.Book)?.item?.author?.let { " — $it" }.orEmpty()}"
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(this@MassMigrationActivity)
                        .setTitle("Review match")
                        .setItems(labels) { _, selected ->
                            entry.candidate = candidates[selected]
                            entry.chapterComparison = null
                            entry.status = "Match selected · review the title and chapter list"
                            updateEntry(entry)
                            updateCount()
                        }
                        .setNegativeButton("No match") { _, _ ->
                            entry.candidate = null
                            entry.status = "Skipped · needs manual migration"
                            updateEntry(entry)
                            updateCount()
                        }
                        .show()
                    entry.status = "${candidates.size} matches found · choose one"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entry.candidate = null
                entry.status = "Search failed: ${e.message ?: "unknown error"}"
            }
            updateEntry(entry)
            updateCount()
        }
    }

    private fun processMigrations() {
        val selected = entries.filter { it.active }
        val matched = selected.filter { it.candidate != null }
        if (matched.isEmpty() || running || autoJob != null || reviewingChapters) return
        val uncheckedChapterLists = matched.count {
            it.candidate is Candidate.Manga && it.chapterComparison == null
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Migrate ${matched.size} entries?")
            .setMessage(buildString {
                append("Each selected match will be processed one at a time. Original entries will remain in your library.")
                if (uncheckedChapterLists > 0) {
                    append("\n\n$uncheckedChapterLists manga chapter list(s) have not been compared. Use “Compare chapter lists” or “Review chapters” first if you want to verify them.")
                }
            })
            .setPositiveButton("Start") { _, _ -> runMigrations(matched) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runMigrations(matched: List<QueueEntry>) {
        running = true
        migrateButton.isEnabled = false
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var completed = 0
            matched.forEachIndexed { index, entry ->
                entry.status = "Migrating ${index + 1} of ${matched.size}…"
                updateEntry(entry)
                try {
                    val result = when (val target = entry.candidate) {
                        is Candidate.Manga -> MangaMigrationManager(app.repo).migrate(
                            from = entry.original,
                            target = target.manga,
                            targetSourceId = target.source.id,
                            options = MangaMigrationOptions(),
                            replaceOriginal = false
                        ).manga.title
                        is Candidate.Book -> {
                            when (entry.kind) {
                                Kind.WEB_NOVEL -> BookMigrationManager(app.repo).migrate(entry.original, target.item).manga.title
                                Kind.EPUB -> {
                                    BookActions.importBook(
                                        this@MassMigrationActivity,
                                        target.item,
                                        categoryId = entry.original.categoryId,
                                        dateAdded = entry.original.dateAdded
                                    )
                                    "imported to local library"
                                }
                                Kind.MANGA -> throw IllegalStateException("Manga needs a manga-source match")
                            }
                        }
                        null -> throw IllegalStateException("Select a destination before migrating")
                    }
                    entry.status = "Succeeded · $result"
                    completed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    entry.status = "Failed: ${e.message ?: "unknown error"}"
                }
                progress.progress = index + 1
                updateEntry(entry)
            }
            running = false
            val failed = matched.size - completed
            MaterialAlertDialogBuilder(this@MassMigrationActivity)
                .setTitle(if (failed == 0) "Migration complete" else "Migration partially complete")
                .setMessage("$completed succeeded\n$failed failed or skipped\n${entries.count { it.active && it.candidate == null }} need manual matching\nOriginal entries were kept.")
                .setPositiveButton("Done") { _, _ -> finish() }
                .setNegativeButton("Review results", null)
                .show()
            updateCount()
        }
    }

    private fun updateEntry(entry: QueueEntry) {
        entry.statusView?.text = entry.status
        entry.candidateView?.text = buildString {
            val sourceName = entry.sourceId?.let { id ->
                entry.sources.firstOrNull { it.first == id }?.second
            }
            if (sourceName != null) append("Destination: ").append(sourceName).append('\n')
            entry.candidate?.let { append("Proposed match: ").append(it.title) }
        }
        val comparison = entry.chapterComparison
        entry.chapterReviewView?.apply {
            visibility = if (entry.candidate is Candidate.Manga) View.VISIBLE else View.GONE
            text = comparison?.let {
                "Chapter comparison: ${it.currentChapters.size} current · ${it.destinationChapters.size} destination · " +
                    "${it.common.size} in both · ${it.currentOnly.size} missing at destination · " +
                    "${it.destinationOnly.size} destination-only"
            }.orEmpty()
        }
        entry.chapterReviewButton?.apply {
            visibility = if (entry.candidate is Candidate.Manga) View.VISIBLE else View.GONE
            text = if (comparison == null) "Review chapters" else "View chapter lists"
        }
    }

    private fun updateCount() {
        val active = entries.filter { it.active }
        val matches = active.count { it.candidate != null }
        val mangaMatches = active.filter { it.candidate is Candidate.Manga }
        val compared = mangaMatches.count { it.chapterComparison != null }
        count.text = "${active.size} entries selected · $matches matches · $compared/${mangaMatches.size} chapter lists compared"
        migrateButton.isEnabled = !running && !reviewingChapters && active.any { it.candidate != null }
        reviewChaptersButton.isEnabled =
            !running && autoJob == null && !reviewingChapters && mangaMatches.isNotEmpty()
    }

    private fun compareChapterLists(entry: QueueEntry) {
        if (running || autoJob != null || reviewingChapters) return
        val candidate = entry.candidate as? Candidate.Manga ?: return
        reviewingChapters = true
        entry.status = "Loading both chapter lists…"
        entry.chapterReviewButton?.isEnabled = false
        updateEntry(entry)
        updateCount()
        lifecycleScope.launch {
            try {
                val comparison = loadChapterComparison(entry, candidate)
                entry.chapterComparison = comparison
                entry.status = "Chapter lists compared · ${comparison.common.size} in both · " +
                    "${comparison.currentOnly.size + comparison.destinationOnly.size} differences"
                updateEntry(entry)
                updateCount()
                showChapterComparison(entry, comparison)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entry.status = "Chapter comparison failed: ${e.message ?: "unknown error"}"
                updateEntry(entry)
                updateCount()
            } finally {
                reviewingChapters = false
                entry.chapterReviewButton?.isEnabled = true
                updateCount()
            }
        }
    }

    private fun compareAllChapterLists() {
        if (running || autoJob != null || reviewingChapters) return
        val targets = entries.filter { it.active && it.candidate is Candidate.Manga }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Find or auto-match manga entries first", Toast.LENGTH_SHORT).show()
            return
        }
        reviewingChapters = true
        reviewChaptersButton.isEnabled = false
        progress.max = targets.size
        progress.progress = 0
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var compared = 0
            var identical = 0
            var different = 0
            var failed = 0
            try {
                targets.forEachIndexed { index, entry ->
                    val candidate = entry.candidate as? Candidate.Manga ?: return@forEachIndexed
                    entry.status = "Comparing chapter lists ${index + 1} of ${targets.size}…"
                    entry.chapterReviewButton?.isEnabled = false
                    updateEntry(entry)
                    try {
                        val result = loadChapterComparison(entry, candidate)
                        entry.chapterComparison = result
                        compared++
                        if (result.currentOnly.isEmpty() && result.destinationOnly.isEmpty()) {
                            identical++
                            entry.status = "Chapter lists match · ${result.common.size} chapters"
                        } else {
                            different++
                            entry.status = "Chapter lists differ · ${result.currentOnly.size} missing at destination · " +
                                "${result.destinationOnly.size} destination-only"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        entry.chapterComparison = null
                        entry.status = "Chapter comparison failed: ${e.message ?: "unknown error"}"
                    } finally {
                        entry.chapterReviewButton?.isEnabled = true
                    }
                    progress.progress = index + 1
                    updateEntry(entry)
                    updateCount()
                }
            } finally {
                progress.visibility = View.GONE
                reviewingChapters = false
                reviewChaptersButton.isEnabled = true
                updateCount()
            }
            MaterialAlertDialogBuilder(this@MassMigrationActivity)
                .setTitle("Chapter list review")
                .setMessage(
                    "$compared manga matches compared\n$identical lists match\n$different lists have differences\n" +
                        "$failed could not be compared\n\nOpen each entry's “View chapter lists” button to inspect chapter names."
                )
                .setPositiveButton("Done", null)
                .show()
        }
    }

    private suspend fun loadChapterComparison(
        entry: QueueEntry,
        candidate: Candidate.Manga
    ): ChapterComparison {
        val currentSource = app.sources.get(entry.original.source)
            ?: throw IllegalStateException("Current source is unavailable")
        val currentDetails = currentSource.getMangaDetails(
            SManga(
                url = entry.original.url,
                title = entry.original.title,
                thumbnailUrl = entry.original.coverUrl,
                author = entry.original.author,
                artist = entry.original.artist,
                description = entry.original.description,
                genre = entry.original.genre,
                status = entry.original.status
            )
        )
        val currentChapters = currentSource.getChapters(currentDetails)
        val destinationDetails = candidate.source.getMangaDetails(candidate.manga)
        val destinationChapters = candidate.source.getChapters(destinationDetails)
        val currentByKey = currentChapters.groupBy(CombinedSource::chapterKey)
        val destinationByKey = destinationChapters.groupBy(CombinedSource::chapterKey)
        val sharedKeys = currentByKey.keys.intersect(destinationByKey.keys)
        return ChapterComparison(
            currentSource = currentSource.name,
            destinationSource = candidate.source.name,
            currentChapters = currentChapters.map { it.name },
            destinationChapters = destinationChapters.map { it.name },
            common = sharedKeys.map { key ->
                currentByKey.getValue(key).first().name to destinationByKey.getValue(key).first().name
            },
            currentOnly = currentChapters.filter { CombinedSource.chapterKey(it) !in destinationByKey }.map { it.name },
            destinationOnly = destinationChapters.filter { CombinedSource.chapterKey(it) !in currentByKey }.map { it.name }
        )
    }

    private fun showChapterComparison(entry: QueueEntry, comparison: ChapterComparison) {
        fun section(title: String, lines: List<String>): TextView = TextView(this).apply {
            text = buildString {
                append(title).append('\n')
                if (lines.isEmpty()) {
                    append("None")
                } else {
                    append(lines.joinToString("\n") { "• $it" })
                }
            }
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(this@MassMigrationActivity).apply {
                text = entry.original.title
                textSize = 14f
            })
            addView(TextView(this@MassMigrationActivity).apply {
                text = "${comparison.currentSource}: ${comparison.currentChapters.size} chapters\n" +
                    "${comparison.destinationSource}: ${comparison.destinationChapters.size} chapters\n" +
                    "Compared by chapter number when available, otherwise by normalized title."
                textSize = 14f
            })
            addView(section(
                "In both lists (${comparison.common.size})",
                comparison.common.map { (current, destination) -> "$current  ↔  $destination" }
            ))
            addView(section("Only in ${comparison.currentSource} (${comparison.currentOnly.size})", comparison.currentOnly))
            addView(section(
                "Only in ${comparison.destinationSource} (${comparison.destinationOnly.size})",
                comparison.destinationOnly
            ))
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Chapter lists")
            .setView(scroll, dp(8), dp(4), dp(8), dp(4))
            .setPositiveButton("Close", null)
            .show()
    }
}
