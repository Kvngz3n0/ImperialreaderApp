package com.imperialreader.ui.browse

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.imperialreader.R

class BrowseFragment : Fragment(R.layout.fragment_browse) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val tabs = view.findViewById<TabLayout>(R.id.tabs)
        val pager = view.findViewById<ViewPager2>(R.id.pager)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) SourcesFragment() else ExtensionsFragment()
        }
        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = if (pos == 0) "Sources" else "Extensions"
        }.attach()

        toolbar.inflateMenu(R.menu.browse_menu)
        val searchView = toolbar.menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = "Search all sources"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                val text = q.orEmpty().trim()
                if (text.isNotBlank()) startActivity(GlobalSearchActivity.intent(requireContext(), text))
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = true
        })
    }
}
