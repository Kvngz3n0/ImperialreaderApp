package com.imperialreader

import android.app.Application
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.imperialreader.data.search.SearchConcurrencyController
import com.imperialreader.data.source.SourceManager
import com.imperialreader.data.storage.StorageManager
import com.imperialreader.data.update.LibraryRefreshConcurrencyController
import com.imperialreader.security.AppLockManager
import com.imperialreader.ui.theme.AppThemeManager
import com.imperialreader.ui.common.CascadeAnimations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class. Acts as a tiny manual dependency container
 * (no Hilt/Dagger so the project stays simple to build).
 */
class App : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs by lazy { AppPrefs(this) }
    val network by lazy { NetworkHelper(this) }
    val db by lazy { AppDatabase.create(this) }
    val storage by lazy { StorageManager(this) }
    val sources by lazy { SourceManager(this) }
    val searchConcurrency by lazy { SearchConcurrencyController(prefs.globalSearchConcurrency) }
    val libraryRefreshConcurrency by lazy {
        LibraryRefreshConcurrencyController { sourceId, operation, queueWaitMs, durationMs ->
            Log.d(
                "LibraryRefresh",
                "source=$sourceId operation=$operation queueWaitMs=$queueWaitMs durationMs=$durationMs"
            )
        }
    }
    val repo by lazy { MangaRepository(this) }
    val downloads by lazy { DownloadManager(this) }
    val appLockManager by lazy { AppLockManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs
        AppThemeManager.initialize(this)
        registerActivityLifecycleCallbacks(appLockManager)
        registerActivityLifecycleCallbacks(CascadeAnimations())
        var startedActivities = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    downloads.pauseAll()
                }
            }
        })
        applicationScope.launch {
            try {
                AutomaticBackupScheduler.schedule(applicationContext)
            } catch (e: Exception) {
                Log.e("App", "Could not schedule automatic backups", e)
            }
            try {
                val oldProfilePictures = java.io.File(filesDir, "profile-pictures")
                if (oldProfilePictures.exists() && !oldProfilePictures.deleteRecursively()) {
                    Log.w("App", "Could not remove old profile picture files")
                }
            } catch (e: Exception) {
                Log.e("App", "Could not remove old profile picture files", e)
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
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
