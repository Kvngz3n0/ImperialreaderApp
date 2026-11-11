package com.imperialreader.ui.browse

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AggregationFragment : Fragment() {
    companion object {
        internal fun create(kind: FeedKind) = AggregationFragment().apply {
            arguments = Bundle().apply { putString("kind", kind.name) }
        }
    }

    private val kind get() = FeedKind.valueOf(requireArguments().getString("kind")!!)
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var status: TextView
    private lateinit var sprite: StarSpriteView
    private lateinit var list: RecyclerView
    private lateinit var adapter: MangaCardAdapter
    private var aggregation: BrowseAggregation? = null
    private var sourceSignature: List<String>? = null
    private var job: Job? = null
    private val openChallenge = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { refreshFeed() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(ctx).apply {
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
            textSize = 14f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(status)
        sprite = StarSpriteView(ctx)
        root.addView(sprite, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ctx.dp(12)
        })
        adapter = MangaCardAdapter(
            onClick = { card -> open(card.tag as FeedEntry) },
            onLongClick = { card ->
                val entry = card.tag as FeedEntry
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(entry.source.name)
                    .setItems(arrayOf("Open entry", "Browse source")) { _, which ->
                        if (which == 0) open(entry)
                        else if (entry.source is BookFeedSource) startActivity(BookBrowseActivity.intent(ctx, entry.source.id.removePrefix("book:")))
                        else startActivity(SourceBrowseActivity.intent(ctx, entry.source.id, latest = true))
                    }.show()
            }
        )
        val grid = GridLayoutManager(ctx, ctx.app.prefs.libraryColumns)
        list = RecyclerView(ctx).apply {
            layoutManager = grid
            adapter = this@AggregationFragment.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && grid.findLastVisibleItemPosition() >= grid.itemCount - 6) loadNext()
                }
            })
        }
        swipe = SwipeRefreshLayout(ctx).apply {
            setColorSchemeColors(Color.TRANSPARENT)
            setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
            addView(list)
            setOnRefreshListener {
                isRefreshing = false
                refresh()
            }
        }
        root.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        refreshIfChanged()
    }

    override fun onDestroyView() {
        job?.cancel()
        job = null
        sprite.stop()
        aggregation = null
        sourceSignature = null
        super.onDestroyView()
    }

    private fun feedQuery() = (parentFragment as? BrowseFragment)?.searchQuery

    internal fun refreshFeed() {
        if (isAdded && view != null) refresh()
    }

    internal fun refreshIfChanged() {
        if (!isAdded || view == null) return
        val app = requireContext().app
        val sources = feedSources()
        val signature = sources.map { it.id } + "adult:${app.prefs.showNsfwExtensions}" + "q:${feedQuery()}"
        if (signature != sourceSignature) {
            sourceSignature = signature
            reset(sources)
        }
    }

    private fun feedSources(): List<com.imperialreader.data.source.Source> {
        val app = requireContext().app
        val pool = if (kind == FeedKind.EXTENSIONS) {
            com.imperialreader.data.book.BookSources.all.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .map { BookFeedSource(it) }
        } else app.sources.enabled()
        val eligible = eligibleSources(pool, kind, app.prefs.showNsfwExtensions)
        return if (feedQuery() == null) eligible else eligible.filter {
            (it as? BookFeedSource)?.let { b -> b.inGlobalSearch } ?: true
        }
    }

    private fun refresh() {
        val app = requireContext().app
        val sources = feedSources()
        sourceSignature = sources.map { it.id } + "adult:${app.prefs.showNsfwExtensions}" + "q:${feedQuery()}"
        reset(sources)
    }

    private fun reset(sources: List<com.imperialreader.data.source.Source>) {
        job?.cancel()
        job = null
        val app = requireContext().app
        val query = feedQuery()
        aggregation = BrowseAggregation(
            sources, app.prefs.globalSearchRequestTimeoutMs, query = query,
            gate = if (query == null) null else { block -> app.searchConcurrency.withPermit { block() } }
        )
        adapter.submitList(emptyList())
        if (sources.isEmpty()) {
            swipe.isRefreshing = false
            sprite.stop()
            status.text = if (kind == FeedKind.ADULT && !requireContext().app.prefs.showNsfwExtensions) {
                "Adult content is hidden. Enable 18+ sources in Settings after age confirmation."
            } else "No enabled sources are available for this section."
            return
        }
        load(retryFailed = false)
    }

    private fun loadNext() {
        val snapshot = aggregation?.snapshot() ?: return
        if (snapshot.hasMore || snapshot.failures.isNotEmpty()) load(retryFailed = true)
    }

    private fun load(retryFailed: Boolean) {
        if (job?.isActive == true) return
        val current = aggregation ?: return
        sprite.start(speed = 1.5f)
        if (current.snapshot().items.isEmpty()) status.text = "Loading latest entries…"
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                current.load(retryFailed = retryFailed) { render(it, loading = true) }
                render(current.snapshot(), loading = false)
                list.post {
                    if (isAdded && current === aggregation && !list.canScrollVertically(1) && current.snapshot().hasMore) loadNext()
                }
            } finally {
                if (current === aggregation) {
                    sprite.stop()
                    swipe.isRefreshing = false
                }
            }
        }
    }

    private fun render(snapshot: FeedSnapshot, loading: Boolean) {
        adapter.submitList(snapshot.items.map { entry ->
            CardItem(
                key = "${entry.source.id}\u0000${entry.manga.url}",
                title = entry.manga.title,
                cover = entry.manga.thumbnailUrl,
                badge1 = entry.source.name,
                headers = entry.source.imageHeaders(),
                tag = entry
            )
        })
        val verbose = requireContext().app.prefs.showBrowseEntryCount
        val failures = snapshot.failures.joinToString("\n") { "${it.source.name}: ${it.message}" }
        val challenged = snapshot.failures.filter { it.challengeUrl != null }
        val query = feedQuery()
        val lines = mutableListOf<String>()
        when {
            loading && snapshot.items.isEmpty() -> lines += if (query == null) "Loading latest entries…" else "Searching for “$query”…"
            snapshot.items.isEmpty() && snapshot.failures.isEmpty() ->
                lines += if (query == null) "No latest entries were returned." else "No results for “$query”."
        }
        if (verbose && snapshot.items.isNotEmpty()) lines += "${snapshot.items.size} entries"
        if (verbose && snapshot.failures.isNotEmpty()) lines += "Some sources failed:\n$failures"
        if (challenged.isNotEmpty()) {
            lines += "Cloudflare check needed: ${challenged.joinToString { it.source.name }} (tap to solve)"
        }
        status.text = lines.joinToString("\n")
        status.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        status.setOnClickListener {
            val challenge = challenged.firstOrNull()?.challengeUrl
            if (challenge != null) {
                openChallenge.launch(com.imperialreader.ui.webview.WebViewActivity.intent(requireContext(), challenge))
            } else if (verbose && snapshot.failures.isNotEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Source failures")
                    .setMessage(failures)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun open(entry: FeedEntry) {
        val ctx = requireContext()
        if (entry.source.nsfw && !ctx.app.prefs.showNsfwExtensions) {
            refreshIfChanged()
            ctx.toast("Adult sources are disabled")
            return
        }
        (entry.source as? BookFeedSource)?.items?.get(entry.manga.url)?.let { book ->
            (activity as? androidx.appcompat.app.AppCompatActivity)?.let { com.imperialreader.ui.books.BookActions.show(it, book) }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val manga = ctx.app.repo.getOrCreate(entry.source.id, entry.manga)
                startActivity(MangaDetailsActivity.intent(ctx, manga.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AggregationFragment", "Could not open source entry", e)
                ctx.toast("Could not open entry: ${e.message ?: "unknown error"}")
            }
        }
    }
}
