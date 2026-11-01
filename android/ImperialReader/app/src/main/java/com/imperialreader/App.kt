package com.imperialreader

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.imperialreader.data.AppPrefs
import com.imperialreader.data.backup.AutomaticBackupScheduler
import com.imperialreader.data.db.AppDatabase
import com.imperialreader.data.download.DownloadManager
import com.imperialreader.data.network.NetworkHelper
import com.imperialreader.data.repo.MangaRepository
import com.imperialreader.data.source.SourceManager
import com.imperialreader.data.storage.StorageManager
import com.imperialreader.security.AppLockManager

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
    val appLockManager by lazy { AppLockManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(appLockManager)
        AutomaticBackupScheduler.schedule(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(network.client)
            .crossfade(true)
            .build()

    val witchToonsCoverImageLoader by lazy {
        ImageLoader.Builder(this)
            .okHttpClient(network.client)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
