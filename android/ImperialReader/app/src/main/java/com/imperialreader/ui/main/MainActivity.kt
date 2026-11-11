package com.imperialreader.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.imperialreader.data.cover.AutomaticCoverRefreshScheduler
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.data.update.AutomaticAppUpdateScheduler
import com.imperialreader.data.update.LibraryUpdateScheduler
import com.imperialreader.ui.common.CascadeAnimations
import com.imperialreader.ui.common.StartupStarAnimation
import com.imperialreader.ui.common.LibraryTransitions
import com.imperialreader.ui.common.transitionDirection
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.imperialreader.R
import com.imperialreader.ui.browse.BrowseFragment
import com.imperialreader.ui.history.HistoryFragment
import com.imperialreader.ui.library.LibraryFragment
import com.imperialreader.ui.more.MoreFragment
import com.imperialreader.ui.updates.UpdatesFragment

class MainActivity : com.imperialreader.ui.theme.ThemedAppCompatActivity() {
    private var startupAnimationPending = false

    companion object {
        private const val EXTRA_LIBRARY_GENRE = "library_genre"
        private var refreshedThisProcess = false
        private var scannedThisProcess = false

        fun librarySearchIntent(context: Context, genre: String): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_LIBRARY_GENRE, genre)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupAnimationPending = savedInstanceState == null
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.nav)
        nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            if (item.itemId == R.id.nav_updates) markUpdatesSeen()
            true
        }
        nav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_updates) markUpdatesSeen()
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_library
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val context = applicationContext
                    AutomaticCoverRefreshScheduler.onAppStart(context)
                    LibraryUpdateScheduler.onAppStart(context)
                    AutomaticAppUpdateScheduler.onAppStart(context)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Could not schedule background tasks", e)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.db.chapterDao().observeUpdateCount().collect { count ->
                    if (count > 0) {
                        nav.getOrCreateBadge(R.id.nav_updates).apply {
                            number = count
                            maxCharacterCount = 3
                        }
                    } else {
                        nav.removeBadge(R.id.nav_updates)
                    }
                }

            }
        }

        if (app.prefs.localCovers && app.prefs.storageUri != null && !scannedThisProcess) {
            scannedThisProcess = true
            lifecycleScope.launch { runCatching { LocalScanner(applicationContext).scan() } }
        }
        val updateInterval = TimeUnit.MINUTES.toMillis(app.prefs.libraryUpdateIntervalMinutes)
        val updateDue = updateInterval == 0L ||
            System.currentTimeMillis() - app.prefs.libraryUpdatedAt >= updateInterval
        if (app.prefs.refreshOnStart && app.prefs.libraryUpdateIntervalMinutes == 0L &&
            updateDue && !refreshedThisProcess
        ) {
            refreshedThisProcess = true
            LibraryUpdateScheduler.enqueueOnAppStart(applicationContext)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun showStartupAnimationIfPending(delayMillis: Long = 0L) {
        if (!startupAnimationPending) return
        startupAnimationPending = false
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) StartupStarAnimation.show(this)
        }, delayMillis)
    }

    override fun onPostResume() {
        super.onPostResume()
        val nav = findViewById<BottomNavigationView>(R.id.nav)
        if (nav.selectedItemId == 0) {
            nav.selectedItemId = R.id.nav_library
        } else {
            show(nav.selectedItemId)
        }
        val genre = intent.getStringExtra(EXTRA_LIBRARY_GENRE)
        if (genre != null) {
            intent.removeExtra(EXTRA_LIBRARY_GENRE)
            nav.selectedItemId = R.id.nav_library
            (supportFragmentManager.findFragmentByTag(R.id.nav_library.toString()) as? LibraryFragment)
                ?.searchGenre(genre)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun markUpdatesSeen() {
        lifecycleScope.launch { app.db.chapterDao().markUpdatesSeen() }
    }

    private fun create(id: Int): Fragment = when (id) {
        R.id.nav_library -> LibraryFragment()
        R.id.nav_updates -> UpdatesFragment()
        R.id.nav_history -> HistoryFragment()
        R.id.nav_browse -> BrowseFragment()
        else -> MoreFragment()
    }

    /** Keeps every tab alive (hide/show) so scroll positions and state survive tab switches. */
    private fun show(id: Int) {
        val fm = supportFragmentManager
        val tag = id.toString()
        val visibleFragments = fm.fragments.filterNot { it.isHidden }
        if (visibleFragments.size == 1 && visibleFragments.single().tag == tag) return
        if (fm.isStateSaved) return
        val previous = visibleFragments.singleOrNull()?.tag?.toIntOrNull()
        val libraryTransition = previous != null && previous != id && LibraryTransitions.enabled(this)
        val nav = findViewById<BottomNavigationView>(R.id.nav)
        val fromIndex = (0 until nav.menu.size()).firstOrNull { nav.menu.getItem(it).itemId == previous } ?: 0
        val toIndex = (0 until nav.menu.size()).firstOrNull { nav.menu.getItem(it).itemId == id } ?: 0
        val tx = fm.beginTransaction()
        if (!libraryTransition) tx.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) tx.add(R.id.container, create(id), tag) else tx.show(existing)
        tx.commitNow()
        if (libraryTransition) {
            val shown = fm.findFragmentByTag(tag)?.view
            if (shown != null) {
                if (!LibraryTransitions.isCascade(this)) {
                    LibraryTransitions.enter(shown, transitionDirection(fromIndex, toIndex))
                } else if (id != R.id.nav_library) {
                    CascadeAnimations.animateContent(shown)
                }
            }
        }
    }
}
