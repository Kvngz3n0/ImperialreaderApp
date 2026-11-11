package com.imperialreader.ui.browse

import android.os.Bundle
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.source.CreatorSearchSource
import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.Source
import com.imperialreader.data.health.HealthRecord
import com.imperialreader.data.health.HealthStatus
import com.imperialreader.data.health.SourceHealth
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun isMediaSource(id: String, isAnime: Boolean): Boolean =
    isAnime || id in setOf("sfmcompile_club", "rule34_tube", "miohentai_com", "rule34_world")

internal fun sourceCategoryName(id: String, isAnime: Boolean, name: String): String =
    if (isMediaSource(id, isAnime)) "$name • Media" else name

private val webcomicSourceIds = setOf(
    "webtoons_en", "tapas_io", "toonkotsu_com", "madara_toonily_com", "ocecomic_com", "thunderscans",
    "witchtoons_net", "wp_philiascans_com", "wp_ravenscans_com",
    "wp_stonescape_xyz", "wp_cocomics_com", "wp_rokaricomics_com", "wp_demonicscans_org",
    "wp_asurascans_com", "wp_manhwaread_com", "manhwafan_com", "manhwazone"
)

internal fun sortReadingSources(sources: List<Source>): List<Source> =
    sources.sortedWith(compareBy<Source> {
        when {
            it.nsfw -> 2
            it.id in webcomicSourceIds -> 1
            else -> 0
        }
    }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })

internal suspend fun searchSourceEntries(source: Source, query: String, page: Int): MangasPage =
    if (source is CreatorSearchSource) source.searchCreators(query, page) else source.search(query, page)

internal fun healthText(record: HealthRecord): String {
    val status = when (record.status) {
        HealthStatus.UNKNOWN -> "Not checked"
        HealthStatus.WORKING -> "Working"
        HealthStatus.UNAVAILABLE -> "Temporarily unavailable"
    }
    val success = if (record.lastSuccess > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(record.lastSuccess)) else "Never"
    return "$status · Failed checks: ${record.failures} · Last success: $success" +
        (record.lastError?.let { "\nLast check error: $it" } ?: "") +
        "\nSource update: unavailable (bundled with app)"
}

internal fun healthColor(status: HealthStatus): Int = when (status) {
    HealthStatus.UNKNOWN -> Color.GRAY
    HealthStatus.WORKING -> Color.rgb(38, 140, 70)
    HealthStatus.UNAVAILABLE -> Color.rgb(190, 65, 50)
}

internal fun addHealthView(parent: ViewGroup): TextView = TextView(parent.context).apply {
    textSize = 12f
    parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

class SourcesFragment : Fragment(R.layout.fragment_sources) {

    companion object {
        private const val ARG_MEDIA = "media"

        fun media() = SourcesFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_MEDIA, true) }
        }
    }

    private val mediaTab get() = arguments?.getBoolean(ARG_MEDIA) == true
    private lateinit var adapter: SourceAdapter
    private var healthJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        adapter = SourceAdapter(
            onOpen = { s, latest -> startActivity(SourceBrowseActivity.intent(requireContext(), s.id, latest)) },
            onLong = { s -> showOptions(s) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        view.findViewById<MaterialButton>(R.id.btnAddSource).apply {
            visibility = if (mediaTab) View.GONE else View.VISIBLE
            setOnClickListener { askCustomSource() }
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        if (!isAdded) return
        val sm = requireContext().app.sources
        val health = SourceHealth(requireContext())
        val visible = sm.visible().filter { isMediaSource(it.id, it.isAnime) == mediaTab }
        val sources = if (mediaTab) visible.sortedBy { it.name.lowercase(Locale.ROOT) } else sortReadingSources(visible)
        val disabled = requireContext().app.prefs.disabledSources
        adapter.submit(
            sources, disabled, if (health.enabled()) health else null
        )
        healthJob?.cancel()
        if (health.enabled()) {
            healthJob = viewLifecycleOwner.lifecycleScope.launch {
                health.checkAll(sources.filter { it.id !in disabled }.map { source ->
                    source.id to suspend { withContext(Dispatchers.IO) { source.getPopular(1).mangas.isNotEmpty() } }
                }) { _, _ -> if (isAdded) adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun showOptions(s: Source) {
        val sm = requireContext().app.sources
        val disabled = s.id in requireContext().app.prefs.disabledSources
        val options = mutableListOf(
            if (disabled) "Enable" else "Disable",
            "Open in WebView"
        )
        if (sm.isCustom(s.id)) options.add("Remove")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(s.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Enable", "Disable" -> sm.setEnabled(s.id, disabled)
                    "Open in WebView" -> startActivity(WebViewActivity.intent(requireContext(), s.baseUrl))
                    "Remove" -> sm.removeCustom(s.id)
                }
                reload()
            }
            .show()
    }

    private fun askCustomSource() {
        val ctx = requireContext()
        val name = EditText(ctx).apply { hint = "Name"; setSingleLine() }
        val url = EditText(ctx).apply { hint = "example.com  (any domain: .com .org .cc …)"; setSingleLine() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(name)
            addView(url)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Add a site by URL")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isBlank() || ctx.app.sources.normalizeBaseUrl(u) == null) {
                    ctx.toast("Enter a name and a valid site address")
                } else if (!ctx.app.sources.addCustom(n, u)) {
                    ctx.toast("That site is already built in")
                } else {
                    ctx.toast("Added — the site type is detected the first time you open it")
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class SourceAdapter(
        val onOpen: (Source, Boolean) -> Unit,
        val onLong: (Source) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        private var items: List<Source> = emptyList()
        private var disabled: Set<String> = emptySet()
        private var health: SourceHealth? = null

        fun submit(list: List<Source>, disabledIds: Set<String>, monitor: SourceHealth?) {
            items = list
            disabled = disabledIds
            health = monitor
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.srcIcon)
            val name: TextView = v.findViewById(R.id.srcName)
            val lang: TextView = v.findViewById(R.id.srcLang)
            val health: TextView = addHealthView(lang.parent as ViewGroup)
            val latest: MaterialButton = v.findViewById(R.id.btnLatest)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            val off = s.id in disabled
            h.icon.text = s.name.take(1).uppercase()
            h.name.text = s.name
            h.lang.text = s.lang.uppercase() + (if (s.isAnime) " • ANIME" else "") + (if (s.nsfw) " • 18+" else "") + (if (off) " • disabled" else "")
            val record = health?.read(s.id)
            h.health.visibility = if (record != null) View.VISIBLE else View.GONE
            if (record != null) {
                h.health.text = if (off) "Disabled · ${healthText(record)}" else healthText(record)
                h.health.setTextColor(healthColor(record.status))
            }
            h.itemView.alpha = if (off) 0.4f else 1f
            h.itemView.setOnClickListener { onOpen(s, false) }
            h.itemView.setOnLongClickListener { onLong(s); true }
            h.latest.setOnClickListener { onOpen(s, true) }
        }
    }
}
