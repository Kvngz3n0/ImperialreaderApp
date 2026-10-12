package com.imperialreader.ui.library

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.imperialreader.R
import com.imperialreader.data.db.CategoryEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.LibraryItem
import com.imperialreader.ui.browse.GlobalSearchActivity
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.LibraryActions
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LibraryFragment : Fragment(R.layout.fragment_library) {

    private val query = MutableStateFlow("")
    private val tick = MutableStateFlow(0)
    private val selectedCategory = MutableStateFlow(-1L) // -1 = All, 0 = Default, else category id
    private var shownCount = 0
    private var rebuildingTabs = false
    private var categories: List<CategoryEntity> = emptyList()

    private lateinit var adapter: MangaCardAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var sprite: StarSpriteView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireContext().app
        val prefs = app.prefs

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe)
        val emptyView = view.findViewById<LinearLayout>(R.id.emptyView)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        val btnGlobal = view.findViewById<Button>(R.id.btnGlobalSearch)
        recycler = view.findViewById(R.id.recycler)
        tabs = view.findViewById(R.id.categoryTabs)
        sprite = view.findViewById(R.id.updateSprite)

        adapter = MangaCardAdapter(
            onClick = { item -> startActivity(MangaDetailsActivity.intent(requireContext(), item.tag as Long)) },
            onLongClick = { item -> showItemMenu(item.tag as Long, item.title) }
        )
        recycler.layoutManager = GridLayoutManager(requireContext(), prefs.libraryColumns)
        recycler.adapter = adapter

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!rebuildingTabs) selectedCategory.value = tab.tag as Long
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        toolbar.inflateMenu(R.menu.library_menu)
        val searchView = toolbar.menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = "Search library"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                val text = q.orEmpty().trim()
                query.value = text
                // Nothing matched locally → fall through to a global search automatically.
                if (text.isNotBlank() && shownCount == 0) openGlobal(text)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                query.value = newText.orEmpty().trim()
                return true
            }
        })
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter -> showFilterDialog()
                R.id.action_sort -> showSortDialog()
                R.id.action_categories -> manageCategories()
            }
            true
        }

        btnGlobal.setOnClickListener { openGlobal(query.value) }

        swipe.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                sprite.start()
                try {
                    val n = app.repo.updateLibrary()
                    requireContext().toast(if (n > 0) "$n new chapter(s)" else "Library is up to date")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    requireContext().toast("Update failed: ${e.message}")
                } finally {
                    sprite.stop()
                    swipe.isRefreshing = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.db.categoryDao().observeAll().collect { rebuildTabs(it) } }
                launch {
                    combine(app.db.mangaDao().observeLibrary(), query, tick, selectedCategory) { items, q, _, cat ->
                        Triple(items, q, process(items, q, cat))
                    }.collect { (all, q, shown) ->
                        shownCount = shown.size
                        adapter.submitList(shown.map { toCard(it) })
                        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
                        when {
                            all.isEmpty() -> {
                                emptyText.text = "Your library is empty.\nAdd manga from Browse, or put files in the local/ folder and scan from More."
                                btnGlobal.visibility = View.GONE
                            }
                            q.isNotBlank() -> {
                                emptyText.text = "No library results for \"$q\""
                                btnGlobal.visibility = View.VISIBLE
                            }
                            else -> {
                                emptyText.text = "Nothing here with the current filters / category."
                                btnGlobal.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = requireContext().app.prefs.libraryColumns
        tick.value = tick.value + 1
    }

    // ---------------------------------------------------------------- categories

    private fun rebuildTabs(list: List<CategoryEntity>) {
        categories = list
        rebuildingTabs = true
        tabs.removeAllTabs()
        if (list.isEmpty()) {
            tabs.visibility = View.GONE
            selectedCategory.value = -1L
        } else {
            tabs.visibility = View.VISIBLE
            val entries = listOf(-1L to "All", 0L to "Default") + list.map { it.id to it.name }
            if (entries.none { it.first == selectedCategory.value }) selectedCategory.value = -1L
            for ((id, name) in entries) {
                val tab = tabs.newTab().setText(name).setTag(id)
                tabs.addTab(tab, id == selectedCategory.value)
            }
        }
        rebuildingTabs = false
    }

    private fun manageCategories() {
        val ctx = requireContext()
        val names = categories.map { it.name }.toTypedArray() + "＋ New category"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Categories")
            .setItems(names) { _, which ->
                if (which == categories.size) askCategoryName(null) else categoryOptions(categories[which])
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun categoryOptions(category: CategoryEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(category.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) askCategoryName(category) else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete “${category.name}”?")
                        .setMessage("Entries in it move back to Default. Nothing is removed from your library.")
                        .setPositiveButton("Delete") { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                val db = requireContext().app.db
                                db.mangaDao().clearCategory(category.id)
                                db.categoryDao().delete(category.id)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun askCategoryName(existing: CategoryEntity?) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Category name"
            setSingleLine()
            setText(existing?.name.orEmpty())
        }
        val box = LinearLayout(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "New category" else "Rename category")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val dao = ctx.app.db.categoryDao()
                    if (existing == null) dao.insert(CategoryEntity(name = name, sortOrder = categories.size))
                    else dao.update(existing.copy(name = name))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- long-press menu

    private fun showItemMenu(mangaId: Long, title: String) {
        val ctx = requireContext()
        val options = arrayOf("Mark as read", "Mark as unread", "Change category", "Remove from library")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setItems(options) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = ctx.app.db
                    val manga = db.mangaDao().getById(mangaId) ?: return@launch
                    when (which) {
                        0 -> {
                            db.chapterDao().setReadForManga(mangaId, true)
                            ctx.toast("Marked all chapters as read")
                        }
                        1 -> {
                            db.chapterDao().setReadForManga(mangaId, false)
                            ctx.toast("Marked all chapters as unread")
                        }
                        2 -> {
                            if (categories.isEmpty()) {
                                ctx.toast("Create a category first (⋮ → Categories)")
                            } else {
                                val picked = LibraryActions.chooseCategory(ctx, manga.categoryId)
                                if (picked != null) db.mangaDao().setCategory(mangaId, picked)
                            }
                        }
                        3 -> ctx.app.repo.toggleFavorite(manga)
                    }
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- list

    private fun process(items: List<LibraryItem>, q: String, category: Long): List<LibraryItem> {
        val prefs = requireContext().app.prefs
        var list = items.filter { q.isBlank() || it.manga.title.contains(q, ignoreCase = true) }
        if (category >= 0) list = list.filter { it.manga.categoryId == category }
        if (prefs.filterUnread) list = list.filter { it.unread > 0 }
        if (prefs.filterBookmarked) list = list.filter { it.bookmarked > 0 }
        if (prefs.filterDownloaded || prefs.downloadedOnly) {
            list = list.filter { it.downloadedCount > 0 || it.manga.source == LOCAL_SOURCE_ID }
        }
        return when (prefs.sortMode) {
            1 -> list.sortedByDescending { it.lastRead ?: 0L }
            2 -> list.sortedByDescending { it.unread }
            3 -> list.sortedByDescending { it.manga.dateAdded }
            4 -> list.sortedByDescending { it.lastUpdated ?: 0L }
            else -> list.sortedBy { it.manga.title.lowercase() }
        }
    }

    private fun toCard(item: LibraryItem): CardItem {
        val prefs = requireContext().app.prefs
        val m = item.manga
        return CardItem(
            key = "m${m.id}",
            title = m.title,
            cover = m.coverUrl,
            badge1 = if (prefs.showUnreadBadge && item.unread > 0) item.unread.toString() else null,
            badge2 = if (!prefs.showDownloadBadge) null else when {
                m.source == LOCAL_SOURCE_ID -> "Local"
                item.downloadedCount > 0 -> "↓${item.downloadedCount}"
                else -> null
            },
            headers = requireContext().app.sources.get(m.source)?.imageHeaders() ?: emptyMap(),
            tag = m.id
        )
    }

    private fun openGlobal(q: String) {
        if (q.isBlank()) return
        startActivity(GlobalSearchActivity.intent(requireContext(), q))
    }

    private fun showFilterDialog() {
        val prefs = requireContext().app.prefs
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Filter")
            .setMultiChoiceItems(
                arrayOf("Unread", "Downloaded", "Bookmarked"),
                booleanArrayOf(prefs.filterUnread, prefs.filterDownloaded, prefs.filterBookmarked)
            ) { _, which, checked ->
                when (which) {
                    0 -> prefs.filterUnread = checked
                    1 -> prefs.filterDownloaded = checked
                    else -> prefs.filterBookmarked = checked
                }
                tick.value = tick.value + 1
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showSortDialog() {
        val prefs = requireContext().app.prefs
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort by")
            .setSingleChoiceItems(
                arrayOf("Alphabetical", "Last read", "Unread count", "Date added", "Recently updated"), prefs.sortMode
            ) { dialog, which ->
                prefs.sortMode = which
                tick.value = tick.value + 1
                dialog.dismiss()
            }
            .show()
    }
}
