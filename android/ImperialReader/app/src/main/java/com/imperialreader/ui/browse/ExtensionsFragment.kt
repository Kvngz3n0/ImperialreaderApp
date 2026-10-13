package com.imperialreader.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.imperialreader.R
import com.imperialreader.data.book.BookSource
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.LOCAL_SOURCE_ID

/** Browse → Extensions: the EPUB and web-novel catalogs (Project Gutenberg, Internet Archive, Royal Road, and more). */
class ExtensionsFragment : Fragment(R.layout.fragment_extensions) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = CatalogAdapter(BookSources.all) { source ->
            if (source.id == LOCAL_SOURCE_ID) {
                startActivity(LocalEntriesActivity.intent(requireContext()))
            } else {
                startActivity(BookBrowseActivity.intent(requireContext(), source.id))
            }
        }
    }

    private class CatalogAdapter(
        val items: List<BookSource>,
        val onOpen: (BookSource) -> Unit
    ) : RecyclerView.Adapter<CatalogAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.extName)
            val info: TextView = v.findViewById(R.id.extInfo)
            val action: MaterialButton = v.findViewById(R.id.extAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            h.name.text = s.name
            h.info.text = s.description
            h.action.text = "Browse"
            h.action.setOnClickListener { onOpen(s) }
            h.itemView.setOnClickListener { onOpen(s) }
        }
    }
}
