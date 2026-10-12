package com.imperialreader.ui.history

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.db.HistoryItem
import com.imperialreader.ui.common.RowAdapter
import com.imperialreader.ui.common.RowItem
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.ui.reader.ReaderActivity
import com.imperialreader.util.app
import com.imperialreader.util.relativeTime
import kotlinx.coroutines.launch

class HistoryFragment : Fragment(R.layout.fragment_history) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireContext().app

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)

        val adapter = RowAdapter(
            actionIcon = R.drawable.ic_delete,
            onClick = { row ->
                val h = row.tag as HistoryItem
                startActivity(ReaderActivity.intent(requireContext(), h.chapterId))
            },
            onLongClick = { row ->
                val h = row.tag as HistoryItem
                startActivity(MangaDetailsActivity.intent(requireContext(), h.mangaId))
            },
            onAction = { row ->
                val h = row.tag as HistoryItem
                viewLifecycleOwner.lifecycleScope.launch { app.db.historyDao().delete(h.chapterId) }
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        toolbar.inflateMenu(R.menu.history_menu)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_clear) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Clear history?")
                    .setMessage("This removes every entry from History. Read progress is kept.")
                    .setPositiveButton("Clear") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch { app.db.historyDao().clear() }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.db.historyDao().observe().collect { list ->
                    adapter.submitList(list.map { h ->
                        val num = if (h.chapterNumber >= 0f) "Ch. ${trim(h.chapterNumber)}" else h.chapterName
                        RowItem(
                            key = "h${h.chapterId}",
                            title = h.mangaTitle,
                            subtitle = num + " • " + relativeTime(h.lastRead),
                            cover = h.mangaCover,
                            headers = app.sources.get(h.mangaSource)?.imageHeaders() ?: emptyMap(),
                            tag = h
                        )
                    })
                    emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun trim(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()
}
