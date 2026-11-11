package com.imperialreader.ui.common

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.PathInterpolator
import android.view.animation.TranslateAnimation
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.tabs.TabLayout
import com.imperialreader.ui.more.MoreFragment
import java.util.WeakHashMap

internal object CascadeTiming {
    const val DURATION_MS = 2000L
    const val STAGGER_MS = 100L
    const val MAX_ITEMS = 8
    fun delay(index: Int): Long = index.coerceIn(0, MAX_ITEMS - 1) * STAGGER_MS
}

/** Installs a one-time entrance on visible collections without changing their adapters or item state. */
class CascadeAnimations : Application.ActivityLifecycleCallbacks {
    companion object {
        private val libraryLists = WeakHashMap<RecyclerView, Boolean>()
        private val libraryRunning = WeakHashMap<View, Animation>()

        fun registerLibrary(list: RecyclerView) {
            libraryLists[list] = true
            list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = Unit
                override fun onChildViewDetachedFromWindow(view: View) {
                    val animation = libraryRunning.remove(view)
                    if (animation != null && view.animation === animation) view.clearAnimation()
                }
            })
        }

        fun animateLibrary(list: RecyclerView, force: Boolean = false) {
            if (!allowed(list.context, force)) return
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!list.isShown || list.childCount == 0) return true
                    if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!allowed(list.context, force) || list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return true
                    val children = (0 until list.childCount).map { list.getChildAt(it) }
                        .filter { list.getChildAdapterPosition(it) != RecyclerView.NO_POSITION }
                        .sortedBy { list.getChildAdapterPosition(it) }
                    if (children.firstOrNull()?.let { list.getChildAdapterPosition(it) } != 0) return true
                    list.itemAnimator?.endAnimations()
                    animateVisible(list.context, children, false, libraryRunning)
                    return true
                }
            }
            list.viewTreeObserver.addOnPreDrawListener(listener)
            list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(listener)
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        }

        /** Cascades a whole tab's content (its list items, or its top-level blocks) when the Cascade transition style is chosen. */
        fun animateContent(root: View) {
            root.post {
                if (!root.isShown || !systemAllows(root.context)) return@post
                val list = findList(root)
                val children = if (list != null) {
                    (0 until list.childCount).map { list.getChildAt(it) }
                        .filter { list.getChildAdapterPosition(it) != RecyclerView.NO_POSITION }
                        .sortedBy { list.getChildAdapterPosition(it) }
                } else {
                    val group = root as? ViewGroup ?: return@post
                    val content = (group.getChildAt(0) as? ViewGroup) ?: group
                    (0 until content.childCount).map { content.getChildAt(it) }
                }
                animateVisible(root.context, children, false, null)
            }
        }

        private fun findList(view: View): RecyclerView? = when (view) {
            is RecyclerView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) }
            else -> null
        }

        private fun systemAllows(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return false
            return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }

        private fun allowed(context: Context, force: Boolean): Boolean =
            systemAllows(context) && (force ||
                PreferenceManager.getDefaultSharedPreferences(context).getBoolean("cascading_animations", false))

        private fun enabled(context: Context): Boolean = allowed(context, false)

        private fun animateVisible(
            context: Context,
            views: List<View>,
            horizontal: Boolean,
            running: MutableMap<View, Animation>?
        ) {
            val visible = views.filter { it.isShown && it.getGlobalVisibleRect(Rect()) }
                .take(CascadeTiming.MAX_ITEMS)
            val distance = 8f * context.resources.displayMetrics.density
            visible.forEachIndexed { index, view ->
                val animation = AnimationSet(true).apply {
                    interpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)
                    duration = CascadeTiming.DURATION_MS
                    startOffset = CascadeTiming.delay(index)
                    addAnimation(AlphaAnimation(0f, 1f))
                    addAnimation(TranslateAnimation(
                        if (horizontal) -distance else 0f, 0f,
                        if (horizontal) 0f else distance, 0f
                    ))
                    if (running != null) setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation) = Unit
                        override fun onAnimationRepeat(animation: Animation) = Unit
                        override fun onAnimationEnd(animation: Animation) { running.remove(view) }
                    })
                }
                running?.put(view, animation)
                view.startAnimation(animation)
            }
        }
    }

    private val controllers = WeakHashMap<Activity, Controller>()

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity !is FragmentActivity || activity.javaClass.name.contains(".ui.reader.") ||
            activity.javaClass.name.contains(".ui.webview.") || activity.javaClass.name.contains(".ui.security.")
        ) return
        val controller = Controller(activity, state == null)
        controllers[activity] = controller
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(controller.fragments, true)
    }

    override fun onActivityStarted(activity: Activity) {
        controllers[activity]?.start()
    }

    override fun onActivityStopped(activity: Activity) {
        controllers[activity]?.stop()
    }

    override fun onActivityDestroyed(activity: Activity) {
        val controller = controllers.remove(activity) ?: return
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(controller.fragments)
        }
        controller.stop()
    }

    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private class Controller(private val activity: Activity, private val fresh: Boolean) {
        private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        private val observed = WeakHashMap<View, Boolean>()
        private val running = WeakHashMap<View, Animation>()
        private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "cascading_animations" && !enabled()) cancelAnimations()
        }
        private var listening = false

        val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
                if (!fresh || state != null) return
                v.post {
                    if (v.isAttachedToWindow) {
                        scan(v)
                        if (f is MoreFragment) animateMore(v)
                    }
                }
            }
        }

        fun start() {
            if (!listening) {
                prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
                listening = true
            }
            if (fresh) activity.window.decorView.post { scan(activity.window.decorView) }
        }

        fun stop() {
            if (listening) {
                prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                listening = false
            }
            cancelAnimations()
        }

        private fun enabled(): Boolean = enabled(activity)

        private fun scan(view: View) {
            when (view) {
                is RecyclerView -> {
                    if (view.parent !is androidx.viewpager2.widget.ViewPager2 && !libraryLists.containsKey(view)) observeList(view)
                }
                is NavigationBarView, is TabLayout -> observeGroup(view as ViewGroup, horizontal = true)
                is ViewGroup -> for (i in 0 until view.childCount) scan(view.getChildAt(i))
            }
        }

        private fun observeList(list: RecyclerView) {
            if (observed.put(list, true) != null) return
            list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = Unit
                override fun onChildViewDetachedFromWindow(view: View) {
                    val animation = running.remove(view)
                    if (animation != null && view.animation === animation) view.clearAnimation()
                }
            })
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!list.isShown || list.childCount == 0) return true
                    if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!enabled() || list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return true
                    val children = (0 until list.childCount).map { list.getChildAt(it) }
                        .filter { list.getChildAdapterPosition(it) != RecyclerView.NO_POSITION }
                        .sortedBy { list.getChildAdapterPosition(it) }
                    if (children.firstOrNull()?.let { list.getChildAdapterPosition(it) } != 0) return true
                    animateVisible(activity, children, horizontal = false, running)
                    return true
                }
            }
            list.viewTreeObserver.addOnPreDrawListener(listener)
            list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(listener)
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        }

        private fun observeGroup(group: ViewGroup, horizontal: Boolean) {
            if (observed.put(group, true) != null) return
            group.post {
                if (!group.isShown || !enabled()) return@post
                val container = (group.getChildAt(0) as? ViewGroup) ?: group
                animateVisible(activity, (0 until container.childCount).map { container.getChildAt(it) }, horizontal, running)
            }
        }

        private fun animateMore(root: View) {
            val content = (root as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
            if (observed.put(content, true) != null || !enabled()) return
            animateVisible(activity, (0 until content.childCount).map { content.getChildAt(it) }, horizontal = false, running)
        }

        private fun cancelAnimations() {
            running.toMap().forEach { (view, animation) ->
                if (view.animation === animation) view.clearAnimation()
            }
            running.clear()
            libraryRunning.toMap().forEach { (view, animation) ->
                if (view.animation === animation) view.clearAnimation()
            }
            libraryRunning.clear()
        }
    }
}
