package com.imperialreader.ui.more

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.imperialreader.R
import com.imperialreader.data.backup.BackupManager
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MoreFragment : Fragment(R.layout.fragment_more) {

    private lateinit var storageSub: TextView
    private lateinit var scanSub: TextView

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

        val downloadedOnly = view.findViewById<MaterialSwitch>(R.id.switchDownloadedOnly)
        val incognito = view.findViewById<MaterialSwitch>(R.id.switchIncognito)
        downloadedOnly.isChecked = prefs.downloadedOnly
        incognito.isChecked = prefs.incognito
        downloadedOnly.setOnCheckedChangeListener { _, checked -> prefs.downloadedOnly = checked }
        incognito.setOnCheckedChangeListener { _, checked -> prefs.incognito = checked }

        view.findViewById<View>(R.id.rowStorage).setOnClickListener { pickFolder.launch(null) }
        view.findViewById<View>(R.id.rowScan).setOnClickListener { scanLocal() }
        view.findViewById<View>(R.id.rowSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
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
