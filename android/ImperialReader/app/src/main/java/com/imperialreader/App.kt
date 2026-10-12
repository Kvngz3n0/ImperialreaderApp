package com.imperialreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.imperialreader.data.AppPrefs
import com.imperialreader.data.db.AppDatabase
import com.imperialreader.data.download.DownloadManager
import com.imperialreader.data.network.NetworkHelper
import com.imperialreader.data.repo.MangaRepository
import com.imperialreader.data.source.SourceManager
import com.imperialreader.data.storage.StorageManager

/**
 * Application class. Acts as a tiny manual dependency container
 * (no Hilt/Dagger so the project stays simple to build).
 */
class App : Application(), ImageLoaderFactory {

    val prefs by lazy { AppPrefs(this) }
    val network by lazy { NetworkHelper(this) }
    val db by lazy { AppDatabase.create(this) }
    val storage by lazy { StorageManager(this) }
    val sources by lazy { SourceManager(this) }
    val repo by lazy { MangaRepository(this) }
    val downloads by lazy { DownloadManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(network.client)
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
