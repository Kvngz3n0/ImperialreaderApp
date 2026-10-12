package com.imperialreader.ui.more

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.imperialreader.R
import com.imperialreader.data.download.DownloadQueueItem

class DownloadQueueAdapter(
    private val onClick: (DownloadQueueItem) -> Unit
) : ListAdapter<DownloadQueueItem, DownloadQueueAdapter.VH>(Diff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.queueTitle)
        val detail: TextView = view.findViewById(R.id.queueDetail)
        val progress: ProgressBar = view.findViewById(R.id.queueProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download_queue, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.mangaTitle
        holder.detail.text = buildString {
            append(item.chapterName)
            append(" • ")
            if (item.downloading) {
                append(if (item.progress > 0) "Downloading · ${item.progress}%" else "Starting download…")
            } else {
                append("Queued")
            }
        }
        holder.progress.visibility = if (item.downloading) View.VISIBLE else View.GONE
        holder.progress.isIndeterminate = item.downloading && item.progress == 0
        if (item.progress > 0) holder.progress.progress = item.progress
        holder.itemView.setOnClickListener { if (item.mangaId != null) onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadQueueItem>() {
        override fun areItemsTheSame(oldItem: DownloadQueueItem, newItem: DownloadQueueItem) =
            oldItem.taskKey == newItem.taskKey

        override fun areContentsTheSame(oldItem: DownloadQueueItem, newItem: DownloadQueueItem) =
            oldItem == newItem
    }
}
