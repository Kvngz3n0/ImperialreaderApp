package com.imperialreader.ui.more

import android.os.Bundle
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.util.toast

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()

            findPreference<Preference>("version")?.summary = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "1.0.0"

            findPreference<SwitchPreferenceCompat>("show_nsfw_ext")?.setOnPreferenceChangeListener { pref, newValue ->
                if (newValue == true) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Confirm your age")
                        .setMessage("This unlocks sources and extensions with adult (18+) content, including MangaDex's erotica and pornographic ratings. Continue only if you are 18 or older.")
                        .setPositiveButton("I'm 18 or older") { _, _ -> (pref as SwitchPreferenceCompat).isChecked = true }
                        .setNegativeButton("Cancel", null)
                        .show()
                    false
                } else {
                    true
                }
            }
            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                ctx.toast("Cache cleared")
                true
            }
            findPreference<Preference>("clear_cookies")?.setOnPreferenceClickListener {
                CookieManager.getInstance().removeAllCookies(null)
                ctx.toast("Cookies cleared")
                true
            }
        }
    }
}
