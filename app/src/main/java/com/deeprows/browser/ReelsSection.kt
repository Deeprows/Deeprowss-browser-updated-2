package com.deeprows.browser

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class ReelsTheme(
    val text: Int,
    val muted: Int,
    val border: Int,
    val surface: Int,
    val surface2: Int,
    val accent: Int
)

/**
 * "Deeprows Reels": a horizontal swipe carousel of video cards with category tabs.
 * Videos come from VideoRepository (YouTube channels chosen in remote/config.json).
 */
class ReelsSection(
    private val activity: Activity,
    private val theme: ReelsTheme,
    private val title: String,
    private val onVideoClick: (VideoItem) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var allVideos: List<VideoItem> = emptyList()
    private var selected = "all"

    private lateinit var chipRow: LinearLayout
    private lateinit var cardRow: LinearLayout
    private lateinit var scroller: HorizontalScrollView

    private val pulses = mutableListOf<ObjectAnimator>()

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(8), 0, dp(6))
        }

        root.addView(buildHeader())

        val chipScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2), 0, dp(8)) }
        }
        chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cardRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(6))
        }
        scroller.addView(cardRow)
        root.addView(scroller)

        showSkeleton()
        load()
        return root
    }

    // ---------------------------------------------------------
    // Header and tabs
    // ---------------------------------------------------------

    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }

        val name = TextView(activity).apply {
            text = "\uD83C\uDFAC ${title.uppercase()}"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.04f
            includeFontPadding = false
            setTextColor(theme.text)
        }

        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val hint = TextView(activity).apply {
            text = "Swipe \u203A"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
        }

        row.addView(dot)
        row.addView(name)
        row.addView(spacer)
        row.addView(hint)
        return row
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        val keys = listOf("all") + VideoRepository.categories()
            .filter { c -> allVideos.any { it.category == c } }
        if (keys.size <= 2) {
            // Only one category: tabs are not needed.
            (chipRow.parent as? View)?.visibility = View.GONE
            return
        }
        (chipRow.parent as? View)?.visibility = View.VISIBLE

        keys.forEach { key ->
            val active = key == selected
            val chip = TextView(activity).apply {
                text = VideoRepository.categoryLabel(key)
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(if (active) Color.WHITE else theme.text)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    if (active) {
                        setColor(theme.accent)
                    } else {
                        setColor(theme.surface2)
                        setStroke(dp(1), theme.border)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                isClickable = true
                setOnClickListener {
                    selected = key
                    buildChips()
                    renderCards()
                }
            }
            chipRow.addView(chip)
        }
    }

    // ---------------------------------------------------------
    // Loading and cards
    // ---------------------------------------------------------

    private fun load() {
        // Your own videos (config.json -> customVideos) appear straight away,
        // without waiting for the YouTube channels to download.
        val mine = VideoRepository.customVideos()
        if (mine.isNotEmpty()) {
            allVideos = mine
            buildChips()
            renderCards()
        }
        scope.launch {
            val videos = try {
                VideoRepository.getVideos()
            } catch (_: Exception) {
                emptyList()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            // Your own videos always come first, even if YouTube can't be reached.
            allVideos = VideoRepository.customVideos() + videos
            buildChips()
            renderCards()
        }
    }

    private fun stopPulses() {
        pulses.forEach { it.cancel() }
        pulses.clear()
    }

    private fun showSkeleton() {
        stopPulses()
        cardRow.removeAllViews()
        repeat(3) {
            val box = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(theme.surface2)
                }
                layoutParams = LinearLayout.LayoutParams(dp(216), dp(222)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
            }
            cardRow.addView(box)
            pulses.add(
                ObjectAnimator.ofFloat(box, "alpha", 0.45f, 1f).apply {
                    duration = 800
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            )
        }
    }

    private fun renderCards() {
        stopPulses()
        cardRow.removeAllViews()

        val list = if (selected == "all") {
            VideoRepository.interleave(allVideos)
        } else {
            allVideos.filter { it.category == selected }
        }

        if (list.isEmpty()) {
            cardRow.addView(TextView(activity).apply {
                text = "Videos are unavailable right now. Check your connection and reopen the app."
                textSize = 11.5f
                setTextColor(theme.muted)
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }

        list.forEach { cardRow.addView(makeCard(it)) }
        scroller.scrollTo(0, 0)
    }

    private fun makeCard(video: VideoItem): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dp(216),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(10), 0) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(theme.surface)
                setStroke(dp(1), theme.border)
            }
            clipToOutline = true
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onVideoClick(video) }
        }

        // ----- thumbnail area -----
        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(150)
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                when (video.type) {
                    "sport" -> intArrayOf(Color.parseColor("#0B5D1E"), Color.parseColor("#1DB954"))
                    "movie" -> intArrayOf(Color.parseColor("#2B1055"), Color.parseColor("#D7263D"))
                    else -> intArrayOf(Color.parseColor("#1F2A44"), Color.parseColor("#6A1B9A"))
                }
            )
        }

        // Fallback picture for cards with no thumbnail: sits behind the image,
        // so it is covered automatically as soon as a real thumbnail loads.
        frame.addView(TextView(activity).apply {
            text = when (video.type) {
                "sport" -> "\u26BD\nSPORT"
                "movie" -> "\uD83C\uDFAC\nMOVIE"
                else -> "\u25B6"
            }
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#66FFFFFF"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = video.title
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(image)
        val embedPage = video.customUrl
        if (video.thumbnail.isNotBlank()) {
            loadImage(video.thumbnail, image)
        } else if (embedPage != null) {
            // No thumbnail in config.json: use the one the player page provides.
            scope.launch {
                val found = try {
                    VideoRepository.embedThumbnail(embedPage)
                } catch (_: Exception) {
                    ""
                }
                if (found.isNotBlank()) loadImage(found, image)
            }
        }

        val scrim = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#99000000"))
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60),
                Gravity.BOTTOM
            )
        }
        frame.addView(scrim)

        val play = TextView(activity).apply {
            text = "\u25B6"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66000000"))
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER)
            setPadding(dp(3), 0, 0, 0)
        }
        frame.addView(play)

        val badge = TextView(activity).apply {
            text = VideoRepository.categoryLabel(video.category)
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(dp(8), dp(8), 0, 0) }
        }
        frame.addView(badge)
        card.addView(frame)

        // ----- text area -----
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        body.addView(TextView(activity).apply {
            text = video.title
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.text)
            maxLines = 2
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        body.addView(TextView(activity).apply {
            val ago = timeAgo(video.published)
            text = if (ago.isEmpty()) video.channel else "${video.channel} \u00B7 $ago"
            textSize = 10.5f
            setTextColor(theme.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })

        card.addView(body)
        return card
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private fun loadImage(url: String, into: ImageView) {
        if (url.isBlank()) return
        imageCache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection().apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"
                        )
                        setRequestProperty("Referer", "https://deeprows.github.io/")
                    }
                    conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null && !activity.isFinishing && !activity.isDestroyed) {
                imageCache.put(url, bitmap)
                into.setImageBitmap(bitmap)
            }
        }
    }

    private fun timeAgo(published: String): String {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val time = format.parse(published)?.time ?: return ""
            val minutes = (System.currentTimeMillis() - time) / 60_000
            when {
                minutes < 1 -> "just now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 60 * 24 -> "${minutes / 60}h ago"
                minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
                minutes < 60 * 24 * 30 -> "${minutes / (60 * 24 * 7)}w ago"
                else -> "${minutes / (60 * 24 * 30)}mo ago"
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private val imageCache = LruCache<String, Bitmap>(24)
    }
}
