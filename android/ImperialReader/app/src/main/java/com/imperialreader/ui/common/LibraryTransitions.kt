package com.imperialreader.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.TranslateAnimation
import com.imperialreader.util.app

internal enum class LibraryTransitionStyle(val fade: Boolean, val slide: Boolean) {
    FADE(true, false),
    SLIDE(false, true),
    FADE_SLIDE(true, true),
    CASCADE(true, false);

    companion object {
        fun fromPreference(value: String): LibraryTransitionStyle = when (value) {
            "slide" -> SLIDE
            "fade_slide" -> FADE_SLIDE
            "cascade" -> CASCADE
            else -> FADE
        }
    }
}

internal fun transitionDirection(from: Int, to: Int): Int = if (to < from) -1 else 1

internal object LibraryTransitions {
    internal const val DURATION_MS = 2_500L

    fun enabled(context: Context): Boolean =
        context.app.prefs.libraryTransitionsEnabled &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    fun isCascade(context: Context): Boolean =
        enabled(context) &&
            LibraryTransitionStyle.fromPreference(context.app.prefs.libraryTransitionStyle) == LibraryTransitionStyle.CASCADE

    fun enter(view: View, direction: Int) {
        if (!enabled(view.context)) return
        val style = LibraryTransitionStyle.fromPreference(view.context.app.prefs.libraryTransitionStyle)
        val distance = 32f * view.resources.displayMetrics.density * direction
        val animation = AnimationSet(true).apply {
            duration = DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            if (style.fade) addAnimation(AlphaAnimation(0f, 1f))
            if (style.slide) addAnimation(TranslateAnimation(distance, 0f, 0f, 0f))
        }
        view.startAnimation(animation)
    }
}
