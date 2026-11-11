package com.imperialreader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.widget.AppCompatImageView
import coil.load
import coil.size.Size
import coil.transform.Transformation
import com.imperialreader.R
import com.imperialreader.data.source.MangaFirePageTransform
import com.imperialreader.util.dp
import kotlin.math.min

class ZoomablePageImageView(context: Context, private val webtoonMode: Boolean) : AppCompatImageView(context) {
    private val pageMatrix = Matrix()
    private val imageBounds = RectF()
    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (drawable == null) return false
            if (userScale > MIN_SCALE) {
                fitPage()
            } else {
                applyScale(2f, event.x, event.y)
            }
            return true
        }
    })

    private var userScale = MIN_SCALE
    private var lastX = 0f
    private var lastY = 0f
    var onZoomChanged: ((Boolean) -> Unit)? = null

    init {
        scaleType = if (webtoonMode) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.MATRIX
    }

    fun fitPage() {
        val page = drawable ?: return
        val pageWidth = page.intrinsicWidth.toFloat()
        val pageHeight = page.intrinsicHeight.toFloat()
        if (width <= 0 || height <= 0 || pageWidth <= 0 || pageHeight <= 0) return
        userScale = MIN_SCALE
        if (webtoonMode) {
            scaleX = MIN_SCALE
            scaleY = MIN_SCALE
            translationX = 0f
            translationY = 0f
            onZoomChanged?.invoke(false)
            return
        }
        val fit = min(width / pageWidth, height / pageHeight)
        val x = (width - pageWidth * fit) / 2f
        val y = (height - pageHeight * fit) / 2f
        pageMatrix.setScale(fit, fit)
        pageMatrix.postTranslate(x, y)
        setImageMatrix(pageMatrix)
        onZoomChanged?.invoke(false)
    }

    fun resetZoom() {
        if (userScale > MIN_SCALE) fitPage()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fitPage()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gestureHandled = doubleTapDetector.onTouchEvent(event)
        val zoomed = userScale > MIN_SCALE
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x
            lastY = event.y
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && zoomed && event.pointerCount == 1) {
            if (webtoonMode) {
                translationX += event.x - lastX
                translationY += event.y - lastY
                clampViewTransform()
            } else {
                pageMatrix.postTranslate(event.x - lastX, event.y - lastY)
                clampImage()
                setImageMatrix(pageMatrix)
            }
            lastX = event.x
            lastY = event.y
        }

        if (zoomed) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        val handled = super.onTouchEvent(event)
        return handled || gestureHandled || zoomed
    }

    private fun applyScale(nextScale: Float, focusX: Float, focusY: Float) {
        val boundedScale = nextScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = boundedScale / userScale
        if (factor == 1f) return
        if (webtoonMode) {
            translationX += (userScale - boundedScale) * (focusX - width / 2f)
            translationY += (userScale - boundedScale) * (focusY - height / 2f)
            scaleX = boundedScale
            scaleY = boundedScale
        } else {
            pageMatrix.postScale(factor, factor, focusX, focusY)
        }
        userScale = boundedScale
        if (webtoonMode) clampViewTransform() else {
            clampImage()
            setImageMatrix(pageMatrix)
        }
        onZoomChanged?.invoke(userScale > MIN_SCALE)
    }

    private fun clampImage() {
        val page = drawable ?: return
        imageBounds.set(0f, 0f, page.intrinsicWidth.toFloat(), page.intrinsicHeight.toFloat())
        pageMatrix.mapRect(imageBounds)
        val dx = when {
            imageBounds.width() <= width -> width / 2f - imageBounds.centerX()
            imageBounds.left > 0f -> -imageBounds.left
            imageBounds.right < width -> width - imageBounds.right
            else -> 0f
        }
        val dy = when {
            imageBounds.height() <= height -> height / 2f - imageBounds.centerY()
            imageBounds.top > 0f -> -imageBounds.top
            imageBounds.bottom < height -> height - imageBounds.bottom
            else -> 0f
        }
        pageMatrix.postTranslate(dx, dy)
    }

    private fun clampViewTransform() {
        val page = drawable ?: return
        val pageWidth = page.intrinsicWidth.toFloat()
        val pageHeight = page.intrinsicHeight.toFloat()
        if (pageWidth <= 0f || pageHeight <= 0f || width <= 0 || height <= 0) return
        val fit = min(width / pageWidth, height / pageHeight)
        val left = (width - pageWidth * fit) / 2f
        val top = (height - pageHeight * fit) / 2f
        val pivotX = width / 2f
        val pivotY = height / 2f
        imageBounds.set(
            pivotX + (left - pivotX) * userScale + translationX,
            pivotY + (top - pivotY) * userScale + translationY,
            pivotX + (left + pageWidth * fit - pivotX) * userScale + translationX,
            pivotY + (top + pageHeight * fit - pivotY) * userScale + translationY
        )
        translationX += when {
            imageBounds.width() <= width -> pivotX - imageBounds.centerX()
            imageBounds.left > 0f -> -imageBounds.left
            imageBounds.right < width -> width - imageBounds.right
            else -> 0f
        }
        translationY += when {
            imageBounds.height() <= height -> pivotY - imageBounds.centerY()
            imageBounds.top > 0f -> -imageBounds.top
            imageBounds.bottom < height -> height - imageBounds.bottom
            else -> 0f
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
    }
}

/**
 * One adapter for both reader styles.
 * - paged modes (LTR/RTL): each page fills the ViewPager2
 * - webtoon mode: full-width images stacked vertically
 */
class PageAdapter(
    private val headers: Map<String, String>,
    private val webtoon: Boolean,
    private val sourceId: String = "",
    private val onSavePage: (ImageView, String) -> Unit,
    private val onZoomChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    private val enhancedVideoControls = sourceId in setOf("sfmcompile_club", "rule34_world", "pawchive_pw", "rule34video", "rule34_tube", "miohentai_com")
    private val boundImages = mutableMapOf<Int, ZoomablePageImageView>()
    private val boundHolders = mutableMapOf<Int, VH>()
    private var activePage = 0

    var pages: List<String> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(
        val root: FrameLayout,
        val image: ZoomablePageImageView,
        val video: VideoView,
        val mediaController: MediaController,
        val videoControlsButton: TextView,
        val videoControlBar: LinearLayout,
        val rewindButton: TextView,
        val playPauseButton: TextView,
        val forwardButton: TextView,
        val speedButton: TextView,
        val seekBar: SeekBar,
        val timeLabel: TextView,
        val fullScreenButton: TextView,
        val progress: ProgressBar,
        val error: TextView
    ) : RecyclerView.ViewHolder(root) {
        var fullScreen = false
        var controlUpdate: Runnable? = null
        var playbackSpeed = 1f
        var mediaPlayer: android.media.MediaPlayer? = null
        var resumeAfterPause = false
        var topBarVisibility = View.GONE
        var bottomBarVisibility = View.GONE
        var nextChapterPromptVisibility = View.GONE
        var systemBarsVisible = false
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(match, if (webtoon) wrap else match)
            if (webtoon) minimumHeight = ctx.dp(320)
        }
        val image = ZoomablePageImageView(ctx, webtoon).apply {
            layoutParams = FrameLayout.LayoutParams(match, if (webtoon) wrap else match)
            if (webtoon) adjustViewBounds = true
        }
        val video = VideoView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(match, match, Gravity.CENTER)
            visibility = View.GONE
        }
        val mediaController = MediaController(ctx).apply { setAnchorView(root) }
        video.setMediaController(mediaController)
        fun videoButton(label: String, description: String): TextView = TextView(ctx).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = ctx.dp(48)
            setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            background = GradientDrawable().apply {
                setColor(0xCC202020.toInt())
                cornerRadius = ctx.dp(12).toFloat()
            }
            text = label
            contentDescription = description
            isClickable = true
            isFocusable = true
        }
        val rewindButton = videoButton("-10", "Rewind 10 seconds")
        val playPauseButton = videoButton("Play", "Play video")
        val forwardButton = videoButton("+10", "Forward 10 seconds")
        val speedButton = videoButton("1x", "Playback speed")
        val seekBar = SeekBar(ctx).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f).apply {
                marginStart = ctx.dp(8)
                marginEnd = ctx.dp(8)
            }
            contentDescription = "Video position"
        }
        val timeLabel = TextView(ctx).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            minWidth = ctx.dp(76)
            gravity = Gravity.CENTER
            text = "0:00 / 0:00"
        }
        val videoControlBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
            background = GradientDrawable().apply {
                setColor(0xDD151515.toInt())
                cornerRadius = ctx.dp(14).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(12))
            }
            visibility = View.GONE
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(seekBar)
                addView(timeLabel)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(6), 0, 0)
                listOf(rewindButton, playPauseButton, forwardButton, speedButton).forEachIndexed { index, button ->
                    addView(button, LinearLayout.LayoutParams(0, wrap, 1f).apply {
                        if (index > 0) marginStart = ctx.dp(8)
                    })
                }
            })
        }
        val videoControlsButton = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = ctx.dp(16)
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC202020.toInt())
                cornerRadius = ctx.dp(20).toFloat()
            }
            text = "VIDEO  |  TAP FOR CONTROLS"
            contentDescription = "Video. Tap to show playback controls"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val fullScreenButton = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = ctx.dp(16)
                marginEnd = ctx.dp(16)
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC202020.toInt())
                cornerRadius = ctx.dp(20).toFloat()
            }
            text = "FULL SCREEN"
            contentDescription = "Enter full screen video"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val progress = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(ctx.dp(36), ctx.dp(36), android.view.Gravity.CENTER)
        }
        val error = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(wrap, wrap, android.view.Gravity.CENTER)
            setTextColor(0xFFA0A0A0.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
            visibility = View.GONE
        }
        root.addView(image)
        root.addView(video)
        root.addView(videoControlsButton)
        root.addView(videoControlBar)
        root.addView(fullScreenButton)
        root.addView(progress)
        root.addView(error)
        return VH(
            root, image, video, mediaController, videoControlsButton, videoControlBar, rewindButton,
            playPauseButton, forwardButton, speedButton, seekBar, timeLabel, fullScreenButton, progress, error
        )
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.image.resetZoom()
        boundHolders[position] = h
        load(h, position)
    }

    fun setActivePage(position: Int) {
        if (position == activePage) return
        val previous = activePage
        activePage = position
        if (previous in pages.indices && isVideoUrl(pages[previous])) {
            boundHolders[previous]?.let { holder ->
                exitFullScreen(holder)
                setVideoPageLayout(holder, false)
                holder.video.stopPlayback()
                holder.video.visibility = View.GONE
                holder.mediaController.hide()
                stopControlUpdates(holder)
                holder.videoControlsButton.visibility = View.GONE
                holder.videoControlBar.visibility = View.GONE
                holder.fullScreenButton.visibility = View.GONE
                holder.videoControlsButton.setOnClickListener(null)
                holder.fullScreenButton.setOnClickListener(null)
                holder.video.setOnClickListener(null)
                holder.progress.visibility = View.GONE
            }
        }
        listOf(previous, position)
            .filter { it in pages.indices && isVideoUrl(pages[it]) }
            .distinct()
            .forEach(::notifyItemChanged)
    }

    fun pauseVideo() {
        boundHolders[activePage]?.takeIf { holder ->
            pages.getOrNull(activePage)?.let { isVideoPage(holder.root.context, it) } == true &&
                holder.video.visibility == View.VISIBLE
        }?.let { holder ->
            holder.resumeAfterPause = holder.video.isPlaying
            holder.video.pause()
        }
    }

    fun resumeVideo() {
        boundHolders[activePage]?.takeIf { holder ->
            pages.getOrNull(activePage)?.let { isVideoPage(holder.root.context, it) } == true &&
                holder.video.visibility == View.VISIBLE
        }?.let { holder ->
            if (!enhancedVideoControls || holder.resumeAfterPause) holder.video.start()
        }
    }

    private fun load(h: VH, position: Int) {
        val url = pages[position]
        boundImages.entries.removeAll { it.value === h.image }
        boundImages[position] = h.image
        h.image.onZoomChanged = { zoomed -> onZoomChanged(position, zoomed) }
        h.error.visibility = View.GONE
        if (isVideoPage(h.root.context, url) && position != activePage) {
            exitFullScreen(h)
            setVideoPageLayout(h, false)
            h.video.stopPlayback()
            h.video.setOnPreparedListener(null)
            h.video.setOnErrorListener(null)
            h.mediaController.hide()
            stopControlUpdates(h)
            h.video.visibility = View.GONE
            h.videoControlsButton.visibility = View.GONE
            h.videoControlBar.visibility = View.GONE
            h.fullScreenButton.visibility = View.GONE
            h.videoControlsButton.setOnClickListener(null)
            h.fullScreenButton.setOnClickListener(null)
            h.video.setOnClickListener(null)
            h.image.load(null)
            h.image.visibility = View.GONE
            h.image.setOnLongClickListener(null)
            h.progress.visibility = View.GONE
            return
        }
        h.progress.visibility = View.VISIBLE
        if (isVideoPage(h.root.context, url)) {
            h.video.stopPlayback()
            setVideoPageLayout(h, true)
            h.image.load(null)
            h.image.visibility = View.GONE
            h.image.setImageDrawable(null)
            h.image.setOnLongClickListener(null)
            h.video.visibility = View.VISIBLE
            h.video.setMediaController(if (enhancedVideoControls) null else h.mediaController)
            h.videoControlsButton.visibility = if (enhancedVideoControls) View.GONE else View.VISIBLE
            h.videoControlBar.visibility = if (enhancedVideoControls) View.VISIBLE else View.GONE
            h.fullScreenButton.visibility = View.VISIBLE
            h.fullScreenButton.text = "FULL SCREEN"
            h.fullScreenButton.contentDescription = "Enter full screen video"
            h.fullScreenButton.setOnClickListener { toggleFullScreen(h) }
            h.videoControlsButton.text = "VIDEO  |  TAP FOR CONTROLS"
            h.videoControlsButton.contentDescription = "Video. Tap to show playback controls"
            h.videoControlsButton.setOnClickListener {
                if (h.mediaController.isShowing) {
                    h.mediaController.hide()
                    h.videoControlsButton.text = "VIDEO  |  TAP FOR CONTROLS"
                    h.videoControlsButton.contentDescription = "Video. Tap to show playback controls"
                } else {
                    h.mediaController.show(0)
                    h.videoControlsButton.text = "VIDEO  |  HIDE CONTROLS"
                    h.videoControlsButton.contentDescription = "Video. Tap to hide playback controls"
                }
            }
            h.video.setOnClickListener {
                if (enhancedVideoControls) {
                    h.videoControlBar.visibility =
                        if (h.videoControlBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    return@setOnClickListener
                }
                if (!h.mediaController.isShowing) {
                    h.mediaController.show(0)
                    h.videoControlsButton.text = "VIDEO  |  HIDE CONTROLS"
                    h.videoControlsButton.contentDescription = "Video. Tap to hide playback controls"
                }
            }
            h.video.setOnPreparedListener { player ->
                if (h.bindingAdapterPosition != position) return@setOnPreparedListener
                if (position != activePage) {
                    h.video.stopPlayback()
                    h.video.visibility = View.GONE
                    h.progress.visibility = View.GONE
                    return@setOnPreparedListener
                }
                h.progress.visibility = View.GONE
                h.root.minimumHeight = 0
                player.isLooping = !isStreamUrl(url)
                if (enhancedVideoControls) {
                    h.playbackSpeed = 1f
                    h.speedButton.text = "1x"
                    h.playPauseButton.text = "Pause"
                    h.playPauseButton.contentDescription = "Pause video"
                    updateControlPosition(h)
                    startControlUpdates(h, position)
                    h.mediaPlayer = player
                }
            }
            if (enhancedVideoControls) configureVideoControls(h)
            h.video.setOnErrorListener { _, _, _ ->
                if (h.bindingAdapterPosition == position) {
                    h.progress.visibility = View.GONE
                    h.error.text = "Video failed to load\nTap to retry"
                    h.error.visibility = View.VISIBLE
                    h.error.setOnClickListener { load(h, position) }
                }
                true
            }
            h.video.setVideoURI(Uri.parse(url), headers)
            if (enhancedVideoControls) h.video.start()
            return
        }
        exitFullScreen(h)
        setVideoPageLayout(h, false)
        h.video.stopPlayback()
        h.video.setOnPreparedListener(null)
        h.video.setOnErrorListener(null)
        h.mediaController.hide()
        stopControlUpdates(h)
        h.video.visibility = View.GONE
        h.videoControlsButton.visibility = View.GONE
        h.videoControlBar.visibility = View.GONE
        h.fullScreenButton.visibility = View.GONE
        h.videoControlsButton.setOnClickListener(null)
        h.fullScreenButton.setOnClickListener(null)
        h.video.setOnClickListener(null)
        h.image.visibility = View.VISIBLE
        h.image.setOnLongClickListener {
            onSavePage(h.image, url)
            true
        }
        h.image.load(url) {
            headers.forEach { (k, v) -> addHeader(k, v) }
            MangaFirePageTransform.offset(url)?.let { transformations(MangaFireImageTransformation(it)) }
            listener(
                onSuccess = { _, _ ->
                    h.progress.visibility = View.GONE
                    h.root.minimumHeight = 0
                    h.image.fitPage()
                },
                onError = { _, _ ->
                    h.progress.visibility = View.GONE
                    h.error.text = "Page ${position + 1} failed to load\nTap to retry"
                    h.error.visibility = View.VISIBLE
                    h.error.setOnClickListener { load(h, position) }
                }
            )
        }
    }

    private class MangaFireImageTransformation(private val offset: Int) : Transformation {
        override val cacheKey = "mangafire-scramble-$offset"

        override suspend fun transform(input: Bitmap, size: Size): Bitmap =
            MangaFirePageTransform.unscramble(input, offset)
    }

    private fun isVideoPage(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (isVideoUrl(url)) return true
        return uri.scheme == "content" &&
            context.contentResolver.getType(uri)?.substringBefore(';')
                ?.equals("video/mp4", ignoreCase = true) == true
    }

    private fun setVideoPageLayout(holder: VH, isVideo: Boolean) {
        val params = holder.root.layoutParams as? RecyclerView.LayoutParams ?: return
        val height = if (webtoon && isVideo) ViewGroup.LayoutParams.MATCH_PARENT
        else if (webtoon) ViewGroup.LayoutParams.WRAP_CONTENT
        else ViewGroup.LayoutParams.MATCH_PARENT
        if (params.height != height) {
            params.height = height
            holder.root.layoutParams = params
        }
        holder.video.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
    }

    private fun configureVideoControls(holder: VH) {
        holder.rewindButton.setOnClickListener {
            holder.video.seekTo((holder.video.currentPosition - 10_000).coerceAtLeast(0))
        }
        holder.forwardButton.setOnClickListener {
            val duration = holder.video.duration
            if (duration > 0) holder.video.seekTo((holder.video.currentPosition + 10_000).coerceAtMost(duration))
        }
        holder.playPauseButton.setOnClickListener {
            if (holder.video.isPlaying) {
                holder.video.pause()
                holder.playPauseButton.text = "Play"
                holder.playPauseButton.contentDescription = "Play video"
            } else {
                holder.video.start()
                holder.playPauseButton.text = "Pause"
                holder.playPauseButton.contentDescription = "Pause video"
            }
        }
        holder.speedButton.setOnClickListener {
            val speeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)
            val next = speeds[(speeds.indexOf(holder.playbackSpeed) + 1) % speeds.size]
            val player = holder.mediaPlayer ?: return@setOnClickListener
            try {
                val playing = holder.video.isPlaying
                player.playbackParams = player.playbackParams.setSpeed(next)
                if (!playing) holder.video.pause()
                holder.playbackSpeed = next
                holder.speedButton.text = "${next}x"
            } catch (e: IllegalArgumentException) {
                android.widget.Toast.makeText(holder.root.context, "Playback speed is not supported", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        holder.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && holder.video.duration > 0) {
                    holder.video.seekTo((holder.video.duration.toLong() * progress / seekBar.max).toInt())
                    updateControlPosition(holder)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun startControlUpdates(holder: VH, position: Int) {
        stopControlUpdates(holder)
        val update = object : Runnable {
            override fun run() {
                if (holder.bindingAdapterPosition != position || position != activePage ||
                    holder.video.visibility != View.VISIBLE
                ) return
                updateControlPosition(holder)
                holder.root.postDelayed(this, 500L)
            }
        }
        holder.controlUpdate = update
        holder.root.post(update)
    }

    private fun updateControlPosition(holder: VH) {
        val duration = holder.video.duration
        val current = holder.video.currentPosition.coerceAtLeast(0)
        if (duration > 0 && !holder.seekBar.isPressed) {
            holder.seekBar.progress = (current.toLong() * holder.seekBar.max / duration).toInt()
        }
        holder.timeLabel.text = "${formatVideoTime(current)} / ${formatVideoTime(duration.coerceAtLeast(0))}"
    }

    private fun formatVideoTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    private fun stopControlUpdates(holder: VH) {
        holder.controlUpdate?.let(holder.root::removeCallbacks)
        holder.controlUpdate = null
        holder.mediaPlayer = null
    }

    private fun toggleFullScreen(holder: VH) {
        val activity = holder.root.context.findActivity() ?: return
        if (holder.fullScreen) {
            exitFullScreen(holder)
            return
        }
        holder.topBarVisibility = activity.findViewById<View>(R.id.readerTop)?.visibility ?: View.GONE
        holder.bottomBarVisibility = activity.findViewById<View>(R.id.readerBottom)?.visibility ?: View.GONE
        holder.nextChapterPromptVisibility =
            activity.findViewById<View>(R.id.nextChapterPrompt)?.visibility ?: View.GONE
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        holder.systemBarsVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
        activity.findViewById<View>(R.id.readerTop)?.visibility = View.GONE
        activity.findViewById<View>(R.id.readerBottom)?.visibility = View.GONE
        activity.findViewById<View>(R.id.nextChapterPrompt)?.visibility = View.GONE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        holder.fullScreen = true
        holder.fullScreenButton.text = "EXIT FULL SCREEN"
        holder.fullScreenButton.contentDescription = "Exit full screen video"
    }

    private fun exitFullScreen(holder: VH) {
        if (!holder.fullScreen) return
        val activity = holder.root.context.findActivity()
        if (activity != null) {
            activity.findViewById<View>(R.id.readerTop)?.visibility = holder.topBarVisibility
            activity.findViewById<View>(R.id.readerBottom)?.visibility = holder.bottomBarVisibility
            activity.findViewById<View>(R.id.nextChapterPrompt)?.visibility = holder.nextChapterPromptVisibility
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            if (holder.systemBarsVisible) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        holder.fullScreen = false
        holder.fullScreenButton.text = "FULL SCREEN"
        holder.fullScreenButton.contentDescription = "Enter full screen video"
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun isVideoUrl(url: String): Boolean =
        (sourceId == "miohentai_com" && Uri.parse(url).host.equals("cdn.miohentai.com", true)) ||
        Uri.parse(url).path.orEmpty().trimEnd('/').substringAfterLast('/')
            .let { it.endsWith(".mp4", ignoreCase = true) || it.endsWith(".webm", ignoreCase = true) || it.endsWith(".m3u8", ignoreCase = true) }

    /** HLS episodes play once; short MP4 clips keep looping as before. */
    private fun isStreamUrl(url: String): Boolean =
        Uri.parse(url).lastPathSegment.orEmpty().substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    override fun onViewRecycled(holder: VH) {
        boundImages.entries.removeAll { it.value === holder.image }
        boundHolders.entries.removeAll { it.value === holder }
        holder.image.resetZoom()
        holder.image.onZoomChanged = null
        holder.image.setImageDrawable(null)
        holder.video.stopPlayback()
        holder.video.setOnPreparedListener(null)
        holder.video.setOnErrorListener(null)
        holder.mediaController.hide()
        stopControlUpdates(holder)
        holder.video.visibility = View.GONE
        holder.videoControlsButton.visibility = View.GONE
        holder.videoControlBar.visibility = View.GONE
        exitFullScreen(holder)
        setVideoPageLayout(holder, false)
        holder.fullScreenButton.visibility = View.GONE
        holder.videoControlsButton.setOnClickListener(null)
        holder.rewindButton.setOnClickListener(null)
        holder.playPauseButton.setOnClickListener(null)
        holder.forwardButton.setOnClickListener(null)
        holder.speedButton.setOnClickListener(null)
        holder.seekBar.setOnSeekBarChangeListener(null)
        holder.fullScreenButton.setOnClickListener(null)
        holder.video.setOnClickListener(null)
        if (webtoon) holder.root.minimumHeight = holder.root.context.dp(320)
        super.onViewRecycled(holder)
    }

    fun resetZoom(position: Int) {
        boundImages[position]?.resetZoom()
    }
}
