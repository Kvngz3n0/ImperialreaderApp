package com.imperialreader.ui.browse

import android.os.Bundle
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R

class BrowseFragment : Fragment(R.layout.fragment_browse) {
    private var directoryId = View.NO_ID
    private var directoryOpen = false
    internal var searchQuery: String? = null
        private set
    private lateinit var directoryBack: OnBackPressedCallback

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val tabs = view.findViewById<TabLayout>(R.id.tabs)
        val pager = view.findViewById<ViewPager2>(R.id.pager)
        directoryId = savedInstanceState?.getInt("directory_id") ?: View.generateViewId()
        directoryOpen = savedInstanceState?.getBoolean("directory_open") == true
        val directory = FrameLayout(requireContext()).apply {
            id = directoryId
            visibility = if (directoryOpen) View.VISIBLE else View.GONE
        }
        (view as LinearLayout).addView(directory, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        tabs.visibility = if (directoryOpen) View.GONE else View.VISIBLE
        pager.visibility = if (directoryOpen) View.GONE else View.VISIBLE
        directoryBack = object : OnBackPressedCallback(directoryOpen) {
            override fun handleOnBackPressed() {
                closeDirectory()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, directoryBack)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 4
            override fun createFragment(position: Int): Fragment =
                when (position) {
                    0 -> AggregationFragment.create(FeedKind.LATEST)
                    1 -> AggregationFragment.create(FeedKind.EXTENSIONS)
                    2 -> AggregationFragment.create(FeedKind.MEDIA)
                    else -> AggregationFragment.create(FeedKind.ADULT)
                }
        }
        TabLayoutMediator(tabs, pager) { tab, pos ->
            val label = when (pos) {
                0 -> "LATEST"
                1 -> "NOVELS"
                2 -> "MEDIA"
                else -> "ADULT"
            }
            tab.text = SpannableString(label).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.attach()

        toolbar.inflateMenu(R.menu.browse_menu)
        toolbar.menu.add("Refresh").setOnMenuItemClickListener {
            if (!directoryOpen) {
                (childFragmentManager.findFragmentByTag("f${pager.currentItem}") as? AggregationFragment)?.refreshFeed()
            }
            true
        }
        toolbar.menu.add("Manage sources").setOnMenuItemClickListener {
            if (directoryOpen) {
                closeDirectory()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Manage sources")
                    .setItems(arrayOf("Reading sources", "Media sources", "Novel catalogue")) { _, which ->
                        childFragmentManager.beginTransaction()
                            .replace(directoryId, when (which) { 0 -> SourcesFragment(); 1 -> SourcesFragment.media(); else -> ExtensionsFragment() }, "source_directory")
                            .commit()
                        directoryOpen = true
                        directoryBack.isEnabled = true
                        tabs.visibility = View.GONE
                        pager.visibility = View.GONE
                        directory.visibility = View.VISIBLE
                        toolbar.title = "Sources"
                    }
                    .show()
            }
            true
        }
        if (directoryOpen) toolbar.title = "Sources"
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (searchQuery != null) {
                    searchQuery = null
                    applySearch()
                }
                return true
            }
        })
        val searchView = toolbar.menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = "Search this section"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                val text = q.orEmpty().trim()
                searchQuery = text.ifBlank { null }
                applySearch()
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = true
        })
    }

    private fun applySearch() {
        val pager = view?.findViewById<ViewPager2>(R.id.pager) ?: return
        (childFragmentManager.findFragmentByTag("f${pager.currentItem}") as? AggregationFragment)?.refreshIfChanged()
    }

    private fun closeDirectory() {
        directoryOpen = false
        directoryBack.isEnabled = false
        childFragmentManager.findFragmentByTag("source_directory")?.let {
            childFragmentManager.beginTransaction().remove(it).commit()
        }
        view?.findViewById<View>(directoryId)?.visibility = View.GONE
        view?.findViewById<View>(R.id.tabs)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.pager)?.visibility = View.VISIBLE
        view?.findViewById<MaterialToolbar>(R.id.toolbar)?.title = "Browse"
        childFragmentManager.fragments.filterIsInstance<AggregationFragment>().forEach { it.refreshIfChanged() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("directory_id", directoryId)
        outState.putBoolean("directory_open", directoryOpen)
        super.onSaveInstanceState(outState)
    }
}
