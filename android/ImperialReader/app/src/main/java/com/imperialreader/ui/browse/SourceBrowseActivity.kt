package com.imperialreader.ui.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.CreatorSearchSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.ui.reader.ReaderActivity
import com.imperialreader.ui.reader.GalleryMediaSaver
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

internal enum class CreatorPagingAction { STOP, LOAD, OFFER_MORE }

internal fun isCreatorSearch(supportsCreatorSearch: Boolean, mode: Int, selectedMode: Int): Boolean =
    supportsCreatorSearch && mode == 2 && selectedMode != 3

internal fun isGalleryBrowse(supportsGeneral: Boolean, supportsCreatorSearch: Boolean, mode: Int, selectedMode: Int): Boolean =
    supportsGeneral && (mode == 3 || (mode == 2 && (!supportsCreatorSearch || selectedMode == 3)))

internal fun creatorPagingAction(
    hasNext: Boolean,
    itemCount: Int,
    lastVisible: Int,
    pagesInBatch: Int
): CreatorPagingAction {
    if (!hasNext || (itemCount > 0 && lastVisible < itemCount - 6)) return CreatorPagingAction.STOP
    return if (pagesInBatch >= 5) CreatorPagingAction.OFFER_MORE else CreatorPagingAction.LOAD
}

class SourceBrowseActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {

    companion object {
        private const val EXTRA_SOURCE = "source_id"
        private const val EXTRA_LATEST = "latest"

        fun intent(ctx: Context, sourceId: String, latest: Boolean = false): Intent =
            Intent(ctx, SourceBrowseActivity::class.java)
                .putExtra(EXTRA_SOURCE, sourceId)
                .putExtra(EXTRA_LATEST, latest)
    }

    private lateinit var source: Source
    private lateinit var adapter: MangaCardAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var loadMore: Button

    private var mode = 0 // 0 popular, 1 latest, 2 search, 3 general
    private var genreOnly = false
    private var query = ""
    private var page = 1
    private var hasNext = true
    private var loading = false
    private var loadJob: Job? = null
    private var browseGeneration = 0
    private var creatorPagesInBatch = 0
    private var searchDebounceJob: Job? = null
    private var cloudflareUrl: String? = null
    private val items = mutableListOf<SManga>()
    private val mediaTypes = mutableMapOf<String, String>()
    private val mediaTypeLimiter = Semaphore(4)
    private var mediaTypeJob: Job? = null
    private var pendingMedia: Pair<String, Map<String, String>>? = null
    private val saveMedia = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination: Uri? ->
        val media = pendingMedia
        pendingMedia = null
        if (destination != null && media != null) {
            lifecycleScope.launch {
                try {
                    GalleryMediaSaver.save(this@SourceBrowseActivity, media.first, media.second, destination)
                    toast("Media saved")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("Could not save media: ${e.message}")
                }
            }
        }
    }
    private var favoriteUrls: Set<String> = emptySet()
    private val openChallengeBrowser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (cloudflareUrl != null) {
                cloudflareUrl = null
                reset()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_browse)

        val generalChip = findViewById<View>(R.id.chipGeneral)
        val found = app.sources.get(intent.getStringExtra(EXTRA_SOURCE).orEmpty())
        if (found == null) {
            toast("Source not found")
            finish()
            return
        }
        source = found
        generalChip.visibility = if (source.supportsGeneralBrowse) View.VISIBLE else View.GONE

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = source.name
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.source_menu)

        val chips = findViewById<ChipGroup>(R.id.chips)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        recycler = findViewById(R.id.recycler)
        loadMore = Button(this).apply {
            text = "Load more creators"
            visibility = View.GONE
            setOnClickListener {
                creatorPagesInBatch = 0
                visibility = View.GONE
                errorView.visibility = View.GONE
                loadPage()
            }
        }
        (recycler.parent as FrameLayout).addView(
            loadMore,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )

        adapter = MangaCardAdapter(
            onClick = { item -> open(item) },
            onLongClick = { item ->
                if (galleryBrowse()) {
                    val post = item.tag as SManga
                    MaterialAlertDialogBuilder(this)
                        .setTitle(post.title)
                        .setItems(arrayOf("Open", "Download media")) { _, choice ->
                            if (choice == 0) openGalleryItem(post) else downloadGalleryItem(post)
                        }.show()
                }
            }
        )
        val lm = GridLayoutManager(this, app.prefs.libraryColumns)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !loading && hasNext && loadMore.visibility != View.VISIBLE &&
                    lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    creatorPagesInBatch = 0
                    loadPage()
                }
            }
        })

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search ${source.name}"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                searchDebounceJob?.cancel()
                applySearchQuery(q.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(350)
                    applySearchQuery(newText.orEmpty())
                }
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchDebounceJob?.cancel()
                query = ""
                if (mode == 2) {
                    mode = selectedBrowseMode(chips)
                    updateBrowsePresentation()
                    reset()
                }
                return true
            }
        })
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_webview -> startActivity(WebViewActivity.intent(this, source.baseUrl))
                R.id.action_genre_search -> {
                    genreOnly = !genreOnly
                    it.isChecked = genreOnly
                    reset()
                }
            }
            true
        }

        mode = if (intent.getBooleanExtra(EXTRA_LATEST, false)) 1 else 0
        if (mode == 1) chips.check(R.id.chipLatest)
        updateBrowsePresentation()
        chips.setOnCheckedStateChangeListener { _, ids ->
            val selected = when (ids.firstOrNull()) {
                R.id.chipLatest -> 1
                R.id.chipGeneral -> 3
                else -> 0
            }
            mode = if (query.isNotBlank()) 2 else selected
            updateBrowsePresentation()
            reset()
        }

        findViewById<Button>(R.id.btnRetry).setOnClickListener { reset() }
        findViewById<Button>(R.id.btnOpenWeb).setOnClickListener {
            openChallengeBrowser.launch(WebViewActivity.intent(this, cloudflareUrl ?: source.baseUrl))
        }

        reset()
    }

    override fun onStart() {
        super.onStart()
        if (::source.isInitialized) {
            lifecycleScope.launch {
                favoriteUrls = app.db.mangaDao().favoriteUrls(source.id).toSet()
                render()
            }
        }
    }

    private fun reset() {
        browseGeneration++
        loadJob?.cancel()
        mediaTypeJob?.cancel()
        mediaTypes.clear()
        loading = false
        items.clear()
        adapter.submitList(emptyList())
        page = 1
        creatorPagesInBatch = 0
        loadMore.visibility = View.GONE
        hasNext = true
        errorView.visibility = View.GONE
        loadPage()
    }

    private fun applySearchQuery(text: String) {
        query = text.trim()
        mode = if (query.isBlank()) {
            selectedBrowseMode(findViewById(R.id.chips))
        } else 2
        updateBrowsePresentation()
        reset()
    }

    private fun updateBrowsePresentation() {
        val gallery = galleryBrowse()
        (recycler.layoutManager as? GridLayoutManager)?.spanCount =
            if (gallery) 2 else app.prefs.libraryColumns
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = source.name
            subtitle = if (gallery) "Tap to view · hold to download" else null
        }
    }

    private fun selectedBrowseMode(chips: ChipGroup): Int = when (chips.checkedChipId) {
        R.id.chipLatest -> 1
        R.id.chipGeneral -> 3
        else -> 0
    }

    private fun creatorSearch(): Boolean =
        isCreatorSearch(source is CreatorSearchSource, mode, selectedBrowseMode(findViewById(R.id.chips)))

    private fun galleryBrowse(): Boolean =
        isGalleryBrowse(source.supportsGeneralBrowse, source is CreatorSearchSource, mode, selectedBrowseMode(findViewById(R.id.chips)))

    private fun creatorBrowse(): Boolean =
        source.supportsGeneralBrowse && (mode in 0..1 || creatorSearch())

    private fun loadPage() {
        if (loading) return
        loading = true
        val generation = browseGeneration
        val creatorBrowse = creatorBrowse()
        if (creatorBrowse) creatorPagesInBatch++
        if (page == 1) progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            var loaded = false
            try {
                favoriteUrls = app.db.mangaDao().favoriteUrls(source.id).toSet()
                val titleResults = when {
                    mode == 0 -> source.getPopular(page)
                    mode == 1 -> source.getLatest(page)
                    mode == 3 -> source.getGeneral(page)
                    creatorSearch() -> (source as CreatorSearchSource).searchCreators(query, page)
                    mode == 2 && source is CreatorSearchSource -> source.search(query, page)
                    mode == 2 && !genreOnly -> source.search(query, page)
                    else -> MangasPage(emptyList(), false)
                }
                var genreError: String? = null
                val genreResults = if (mode == 2 && source !is CreatorSearchSource && query.isNotBlank() && (genreOnly || query.length >= 2)) {
                    try {
                        withTimeout(12_000L) { source.searchByGenre(query, page) }
                    } catch (e: TimeoutCancellationException) {
                        genreError = "Genre search timed out"
                        MangasPage(emptyList(), false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        genreError = e.message ?: "Genre search failed"
                        MangasPage(emptyList(), false)
                    }
                } else MangasPage(emptyList(), false)
                (titleResults.mangas + genreResults.mangas).forEach { m ->
                    if (items.none { it.url == m.url }) items.add(m)
                }
                hasNext = titleResults.hasNextPage || genreResults.hasNextPage
                page++
                loaded = true
                render()
                if (galleryBrowse()) loadMediaTypes()
                if (items.isEmpty()) {
                    if (!creatorBrowse || !hasNext) {
                        showError(genreError?.let { "Genre search failed: $it" } ?: "No results")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (items.isEmpty()) {
                    val challenge = (e as? CloudflareException)?.url
                    showError(e.message ?: "Failed to load", challenge)
                }
                else {
                    toast(e.message ?: "Failed to load more")
                    if (creatorBrowse) loadMore.visibility = View.VISIBLE
                }
            } finally {
                if (generation == browseGeneration) {
                    loading = false
                    progress.visibility = View.GONE
                    if (!loaded && creatorBrowse && items.isNotEmpty()) loadMore.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun continueCreatorBrowse() {
        val lastVisible = (recycler.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
        when (creatorPagingAction(hasNext, adapter.itemCount, lastVisible, creatorPagesInBatch)) {
            CreatorPagingAction.STOP -> loadMore.visibility = View.GONE
            CreatorPagingAction.LOAD -> loadPage()
            CreatorPagingAction.OFFER_MORE -> {
                if (items.isEmpty()) showError("No creators found yet. Load more pages to continue.")
                loadMore.visibility = View.VISIBLE
            }
        }
    }

    private fun showError(message: String, challengeUrl: String? = null) {
        cloudflareUrl = challengeUrl
        errorText.text = message
        findViewById<View>(R.id.btnOpenWeb).visibility = if (challengeUrl != null) View.VISIBLE else View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun render() {
        val generation = browseGeneration
        val headers = source.imageHeaders()
        adapter.submitList(items.map {
            CardItem(
                key = it.url,
                title = it.title,
                cover = it.thumbnailUrl,
                badge1 = if (it.url in favoriteUrls) "In library" else null,
                badge2 = if (galleryBrowse()) mediaTypes[it.url] else null,
                headers = headers,
                tag = it
            )
        }) {
            if (creatorBrowse() && page > 1) {
                recycler.doOnPreDraw {
                    if (generation == browseGeneration && !loading) continueCreatorBrowse()
                }
                recycler.invalidate()
            }
        }
    }

    private fun loadMediaTypes() {
        mediaTypeJob?.cancel()
        val pending = items.filterNot { it.url in mediaTypes }
        mediaTypeJob = lifecycleScope.launch {
            kotlinx.coroutines.coroutineScope {
                pending.forEach { item ->
                    launch {
                        try {
                            val kind = mediaTypeLimiter.withPermit { source.galleryMediaType(item) }
                            mediaTypes[item.url] = kind ?: "Unknown"
                            render()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("SourceBrowseActivity", "Could not identify gallery media", e)
                            mediaTypes[item.url] = "Unknown"
                            render()
                        }
                    }
                }
            }
        }
    }

    private fun downloadGalleryItem(post: SManga) {
        lifecycleScope.launch {
            try {
                val chapters = source.getChapters(post)
                val chapter = chapters.firstOrNull() ?: throw IOException("No media found for this item")
                val media = source.loadPages(post, chapter)
                if (media.urls.isEmpty()) throw IOException("No media found for this item")
                fun choose(url: String) {
                    pendingMedia = url to media.headers
                    val name = if (source.id == "miohentai_com") {
                        post.title.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".mp4"
                    } else Uri.parse(url).lastPathSegment?.substringBefore('?')
                        ?.takeIf { it.isNotBlank() } ?: "ImperialReader-media"
                    saveMedia.launch(name)
                }
                if (media.urls.size == 1) choose(media.urls.first())
                else MaterialAlertDialogBuilder(this@SourceBrowseActivity)
                    .setTitle("Choose media to download")
                    .setItems(media.urls.mapIndexed { index, _ -> "Media ${index + 1}" }.toTypedArray()) { _, index ->
                        choose(media.urls[index])
                    }.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not prepare download: ${e.message}")
            }
        }
    }

    private fun open(item: CardItem) {
        val manga = item.tag as SManga
        if (galleryBrowse()) {
            openGalleryItem(manga)
            return
        }
        lifecycleScope.launch {
            val entity = app.repo.getOrCreate(source.id, manga)
            startActivity(MangaDetailsActivity.intent(this@SourceBrowseActivity, entity.id))
        }
    }

    private fun openGalleryItem(post: SManga) {
        lifecycleScope.launch {
            try {
                val entity = app.repo.getOrCreate(source.id, post)
                val chapters = source.getChapters(post)
                if (chapters.isEmpty()) throw IOException("${source.name} returned no media for this post")
                app.repo.syncChapters(entity.id, chapters, trackUpdates = false)
                val chapter = app.db.chapterDao().getAll(entity.id)
                    .firstOrNull { it.url == chapters.first().url }
                    ?: throw IOException("${source.name} did not save the selected media")
                startActivity(ReaderActivity.intent(this@SourceBrowseActivity, chapter.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Could not open this gallery item")
            }
        }
    }
}
