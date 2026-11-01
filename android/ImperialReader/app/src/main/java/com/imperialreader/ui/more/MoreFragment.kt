package com.imperialreader.ui.more

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import coil.load
import com.imperialreader.R
import com.imperialreader.data.backup.BackupManager
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MoreFragment : Fragment(R.layout.fragment_more) {

    private lateinit var storageSub: TextView
    private lateinit var scanSub: TextView
    private lateinit var autoBackupSub: TextView
    private lateinit var queueSummary: TextView
    private lateinit var queueEmpty: TextView
    private lateinit var queueRecycler: RecyclerView
    private lateinit var queueAdapter: DownloadQueueAdapter
    private lateinit var profileAvatar: ImageView
    private lateinit var appUpdateSub: TextView

    private val chooseProfilePicture =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    requireContext().app.prefs.profilePictureUri = uri.toString()
                    showProfilePicture(uri)
                } catch (e: SecurityException) {
                    requireContext().toast("Could not keep access to this picture: ${e.message}")
                }
            }
        }

    private val allowUnknownSources =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (requireContext().packageManager.canRequestPackageInstalls()) {
                pendingUpdateApk?.let(::installUpdateApk)
            } else {
                pendingUpdateApk = null
                appUpdateSub.text = "Allow installs from Imperial Reader to update"
            }
        }

    private var pendingUpdateApk: java.io.File? = null

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val n = BackupManager(requireContext()).export(uri)
                    requireContext().toast("Backed up $n library entries")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    requireContext().toast("Backup failed: ${e.message}")
                }
            }
        }
    }

    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val n = BackupManager(requireContext()).restore(uri)
                    requireContext().toast("Restored $n library entries")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    requireContext().toast("Restore failed: ${e.message}")
                }
            }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val ok = requireContext().app.storage.setRoot(uri)
            requireContext().toast(
                if (ok) "Folder ready: downloads/, local/ and autobackup/ created"
                else "Could not create the folders — pick a different location"
            )
            refreshStorageText()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().app.prefs

        storageSub = view.findViewById(R.id.storageSub)
        scanSub = view.findViewById(R.id.scanSub)
        autoBackupSub = view.findViewById(R.id.autoBackupSub)
        queueSummary = view.findViewById(R.id.queueSummary)
        queueEmpty = view.findViewById(R.id.queueEmpty)
        queueRecycler = view.findViewById(R.id.downloadQueue)
        queueAdapter = DownloadQueueAdapter(
            onClick = { item ->
                item.mangaId?.let { startActivity(MangaDetailsActivity.intent(requireContext(), it)) }
            },
            onPauseAllForEntry = { mangaId, allPaused ->
                if (allPaused) requireContext().app.downloads.resumeManga(mangaId)
                else requireContext().app.downloads.pauseManga(mangaId)
            }
        )
        queueRecycler.layoutManager = LinearLayoutManager(requireContext())
        queueRecycler.adapter = queueAdapter
        refreshAutomaticBackupText()
        profileAvatar = view.findViewById(R.id.profileAvatar)
        appUpdateSub = view.findViewById(R.id.appUpdateSub)
        profileAvatar.setOnClickListener { chooseProfilePicture.launch(arrayOf("image/*")) }
        view.findViewById<View>(R.id.rowProfile)
            .setOnClickListener { chooseProfilePicture.launch(arrayOf("image/*")) }
        prefs.profilePictureUri?.let { showProfilePicture(Uri.parse(it)) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().app.downloads.queueItems.collect { items ->
                    val active = items.count { it.downloading && !it.paused }
                    val paused = items.count { it.paused }
                    queueSummary.text = when {
                        items.isEmpty() -> "Nothing is queued"
                        active == 0 && paused > 0 -> "$paused download(s) paused"
                        active == 0 -> "${items.size} chapter(s) waiting"
                        items.size == 1 -> "1 chapter downloading"
                        else -> "$active downloading · ${items.size - active - paused} queued · $paused paused"
                    }
                    queueEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    queueRecycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                    queueAdapter.submitQueue(items)
                }
            }
        }

        val downloadedOnly = view.findViewById<MaterialSwitch>(R.id.switchDownloadedOnly)
        val incognito = view.findViewById<MaterialSwitch>(R.id.switchIncognito)
        downloadedOnly.isChecked = prefs.downloadedOnly
        incognito.isChecked = prefs.incognito
        downloadedOnly.setOnCheckedChangeListener { _, checked -> prefs.downloadedOnly = checked }
        incognito.setOnCheckedChangeListener { _, checked -> prefs.incognito = checked }

        view.findViewById<View>(R.id.rowStorage).setOnClickListener { pickFolder.launch(null) }
        view.findViewById<View>(R.id.rowScan).setOnClickListener { scanLocal() }
        view.findViewById<View>(R.id.rowAutoBackup).setOnClickListener { createAutomaticBackupNow() }
        view.findViewById<View>(R.id.rowSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.rowAppUpdate).setOnClickListener { checkForAppUpdate() }
        view.findViewById<View>(R.id.rowBackup).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Backup and restore")
                .setItems(arrayOf("Create backup", "Restore backup")) { _, which ->
                    if (which == 0) {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
                        createBackup.launch("imperial_reader_backup_$stamp.json")
                    } else {
                        restoreBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                }
                .show()
        }
        view.findViewById<View>(R.id.rowAbout).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Imperial Reader")
                .setMessage("A reader app that combines novels and manga/Manhwa/manhua")
                .setPositiveButton("OK", null)
                .show()
        }
        refreshStorageText()
    }

    private fun refreshStorageText() {
        storageSub.text = requireContext().app.storage.displayPath()
        refreshAutomaticBackupText()
    }

    private fun showProfilePicture(uri: Uri) {
        profileAvatar.load(uri) {
            placeholder(R.drawable.ic_profile)
            error(R.drawable.ic_profile)
        }
    }

    private fun checkForAppUpdate() {
        appUpdateSub.text = "Checking the V9 release…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apk = com.imperialreader.data.update.AppUpdateManager(requireContext())
                    .downloadLatestApk()
                pendingUpdateApk = apk
                appUpdateSub.text = "Update downloaded · tap to install"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    !requireContext().packageManager.canRequestPackageInstalls()
                ) {
                    allowUnknownSources.launch(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                } else {
                    installUpdateApk(apk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appUpdateSub.text = "Update check failed · tap to retry"
                requireContext().toast("App update failed: ${e.message}")
            }
        }
    }

    private fun installUpdateApk(apk: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                apk
            )
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            appUpdateSub.text = "Could not open installer · tap to retry"
            requireContext().toast("Could not start APK installer: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::autoBackupSub.isInitialized) refreshAutomaticBackupText()
    }

    private fun refreshAutomaticBackupText() {
        if (!::autoBackupSub.isInitialized) return
        val prefs = requireContext().app.prefs
        val last = prefs.automaticBackupAt
        val status = prefs.automaticBackupStatus
        val time = if (last > 0L) {
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(last))
        } else {
            "not run yet"
        }
        autoBackupSub.text = buildString {
            append("Every 12 hours · keeps 5 · Last: ").append(time)
            if (!status.isNullOrBlank()) append(" · ").append(status)
        }
    }

    private fun createAutomaticBackupNow() {
        val context = requireContext().applicationContext
        autoBackupSub.text = "Creating backup…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val count = BackupManager(context).createAutomaticBackup()
                context.app.prefs.automaticBackupAt = System.currentTimeMillis()
                context.app.prefs.automaticBackupStatus = "Saved $count library entries"
                refreshAutomaticBackupText()
                context.toast("Automatic backup saved ($count entries)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.app.prefs.automaticBackupStatus = "Backup failed: ${e.message.orEmpty().take(100)}"
                refreshAutomaticBackupText()
                context.toast("Automatic backup failed: ${e.message}")
            }
        }
    }

    private fun scanLocal() {
        viewLifecycleOwner.lifecycleScope.launch {
            scanSub.text = "Scanning…"
            try {
                val n = LocalScanner(requireContext()).scan()
                scanSub.text = "Imported $n local entr${if (n == 1) "y" else "ies"}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanSub.text = e.message ?: "Scan failed"
            }
        }
    }
}
