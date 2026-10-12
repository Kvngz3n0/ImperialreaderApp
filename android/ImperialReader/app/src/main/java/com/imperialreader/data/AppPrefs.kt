package com.imperialreader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class AppPrefs(context: Context) {

    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        const val READER_LTR = 0
        const val READER_RTL = 1
        const val READER_VERTICAL = 2
        const val DEFAULT_REPO = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
    }

    /** 0 = left-to-right, 1 = right-to-left, 2 = vertical/webtoon (stored as string by ListPreference). */
    var readerMode: Int
        get() = sp.getString("reader_mode", "1")?.toIntOrNull() ?: READER_RTL
        set(v) = sp.edit { putString("reader_mode", v.toString()) }

    val keepScreenOn: Boolean get() = sp.getBoolean("keep_screen_on", true)
    val fullscreen: Boolean get() = sp.getBoolean("fullscreen", true)
    val tapNavigation: Boolean get() = sp.getBoolean("tap_navigation", true)
    val volumeKeys: Boolean get() = sp.getBoolean("volume_keys", false)
    val showNsfwExtensions: Boolean get() = sp.getBoolean("show_nsfw_ext", false)

    val libraryColumns: Int get() = sp.getString("library_columns", "3")?.toIntOrNull() ?: 3

    var incognito: Boolean
        get() = sp.getBoolean("incognito", false)
        set(v) = sp.edit { putBoolean("incognito", v) }

    var downloadedOnly: Boolean
        get() = sp.getBoolean("downloaded_only", false)
        set(v) = sp.edit { putBoolean("downloaded_only", v) }

    var searchCombine: Boolean
        get() = sp.getBoolean("search_combine", true)
        set(v) = sp.edit { putBoolean("search_combine", v) }

    var searchOnlyResults: Boolean
        get() = sp.getBoolean("search_only_results", false)
        set(v) = sp.edit { putBoolean("search_only_results", v) }

    var storageUri: String?
        get() = sp.getString("storage_uri", null)
        set(v) = sp.edit { putString("storage_uri", v) }

    var automaticBackupAt: Long
        get() = sp.getLong("automatic_backup_at", 0L)
        set(v) = sp.edit { putLong("automatic_backup_at", v) }

    var automaticBackupStatus: String?
        get() = sp.getString("automatic_backup_status", null)
        set(v) = sp.edit { putString("automatic_backup_status", v) }

    // ---- library state ----
    var sortMode: Int
        get() = sp.getInt("library_sort", 0)
        set(v) = sp.edit { putInt("library_sort", v) }
    var filterUnread: Boolean
        get() = sp.getBoolean("filter_unread", false)
        set(v) = sp.edit { putBoolean("filter_unread", v) }
    var filterBookmarked: Boolean
        get() = sp.getBoolean("filter_bookmarked", false)
        set(v) = sp.edit { putBoolean("filter_bookmarked", v) }

    val showUnreadBadge: Boolean get() = sp.getBoolean("badge_unread", true)
    val showDownloadBadge: Boolean get() = sp.getBoolean("badge_download", true)
    val refreshOnStart: Boolean get() = sp.getBoolean("refresh_on_start", false)

    /** Extract and keep refreshing covers of local entries (from cover.jpg, archives, EPUBs). */
    val localCovers: Boolean get() = sp.getBoolean("local_covers", true)

    var filterDownloaded: Boolean
        get() = sp.getBoolean("filter_downloaded", false)
        set(v) = sp.edit { putBoolean("filter_downloaded", v) }

    // ---- sources ----
    var disabledSources: Set<String>
        get() = sp.getStringSet("disabled_sources", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("disabled_sources", v) }

    /** Entries look like "Name|https://site.tld". */
    var customMadara: Set<String>
        get() = sp.getStringSet("custom_madara", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("custom_madara", v) }

    var repos: List<String>
        get() = (sp.getStringSet("repos", null) ?: setOf(DEFAULT_REPO)).toList()
        set(v) = sp.edit { putStringSet("repos", v.toSet()) }
}
