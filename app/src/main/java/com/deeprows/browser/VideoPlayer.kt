package com.deeprows.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen in-app video player. Opens on top of the home page, plays straight
 * away, and closes with the X button or the phone's Back button.
 */
class VideoPlayer(private val activity: Activity) {

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var forceLandscape = false

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        video: VideoItem,
        playlist: List<VideoItem> = listOf(video),
        onOpenOnYouTube: (String) -> Unit
    ) {
        // Swipe up = next video, swipe down = previous (same order as the cards).
        val list = playlist.ifEmpty { listOf(video) }
        var index = list.indexOfFirst {
            it.videoId == video.videoId && it.customUrl == video.customUrl
        }.coerceAtLeast(0)
        var topBar: View? = null
        var busy = false

        // Every video in the "deeprows" category opens full screen in landscape.
        forceLandscape = video.category == "deeprows"
        originalOrientation = activity.requestedOrientation
        if (forceLandscape) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        var onSwipe: (Int) -> Unit = {}

        val swipeDetector = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    val dy = e2.y - start.y
                    val dx = e2.x - start.x
                    if (
                        Math.abs(dy) > dp(90) &&
                        Math.abs(dy) > Math.abs(dx) * 1.5f &&
                        Math.abs(velocityY) > 700
                    ) {
                        onSwipe(if (dy < 0) 1 else -1)
                    }
                    return false
                }
            }
        )

        // Watches every touch for an up/down swipe without taking it away from the
        // player, so taps and the player's own controls keep working.
        val root = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                swipeDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply { setBackgroundColor(Color.BLACK) }

        val spinner = ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
        }

        val web = WebView(activity).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                previousOrientation = activity.requestedOrientation
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                hideCustomView(root)
            }
        }

        web.webViewClient = object : WebViewClient() {
            // Keep the player on the video: block the page itself from navigating away.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = request?.isForMainFrame == true

            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = View.GONE
            }
        }

        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(spinner)

        fun makeNavButton(label: String, description: String, topMargin: Int, bottomMargin: Int) =
            TextView(activity).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = description
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#88000000"))
                    setStroke(dp(1), Color.parseColor("#88FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    setMargins(0, topMargin, 0, bottomMargin)
                }
                isClickable = true
            }

        val navNext = makeNavButton("\u25B2", "Next video", 0, dp(8))
        val navPrev = makeNavButton("\u25BC", "Previous video", 0, 0)

        root.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                alpha = 0.75f
                addView(navNext)
                addView(navPrev)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL
                ).apply { setMargins(0, 0, dp(10), 0) }
            }
        )

        fun applyMode(v: VideoItem) {
            forceLandscape = v.category == "deeprows"
            activity.requestedOrientation =
                if (forceLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else originalOrientation
            dialog.window?.let { w ->
                val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (forceLandscape) {
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        fun showVideo(v: VideoItem) {
            applyMode(v)
            topBar?.let { root.removeView(it) }
            val position = if (list.size > 1) "${index + 1}/${list.size}" else ""
            val bar = buildTopBar(v, dialog, onOpenOnYouTube, position)
            topBar = bar
            root.addView(bar)
            navNext.alpha = if (index < list.size - 1) 1f else 0.3f
            navPrev.alpha = if (index > 0) 1f else 0.3f
            spinner.visibility = View.VISIBLE
            web.loadDataWithBaseURL(
                "https://deeprows.github.io/",
                pageFor(v),
                "text/html",
                "utf-8",
                null
            )
        }

        fun go(step: Int) {
            val target = index + step
            if (busy || target < 0 || target >= list.size) return
            busy = true
            index = target
            val shift = root.height * 0.12f * step
            web.animate().translationY(-shift).alpha(0f).setDuration(120).withEndAction {
                showVideo(list[index])
                web.translationY = shift
                web.animate().translationY(0f).alpha(1f).setDuration(180)
                    .withEndAction { busy = false }
            }
        }

        onSwipe = { step -> go(step) }
        navNext.setOnClickListener { go(1) }
        navPrev.setOnClickListener { go(-1) }

        dialog.setContentView(root)

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP &&
                customView != null
            ) {
                hideCustomView(root)
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            hideCustomView(root)
            activity.requestedOrientation = originalOrientation
            root.removeView(web)
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }

        dialog.show()

        // Every video is shown through its own embedding player, sized to fill the
        // screen. The base URL tells the host which site is embedding the player.
        showVideo(list[index])
    }

    private fun hideCustomView(root: FrameLayout) {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        activity.requestedOrientation = previousOrientation
    }

    private fun buildTopBar(
        video: VideoItem,
        dialog: Dialog,
        onOpenOnYouTube: (String) -> Unit,
        position: String = ""
    ): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (forceLandscape) {
                setPadding(dp(18), dp(10), dp(18), dp(22))
            } else {
                setPadding(dp(14), statusBarHeight() + dp(8), dp(12), dp(22))
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(activity).apply {
            text = video.title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            text = if (position.isEmpty()) video.channel else "${video.channel} \u00B7 $position"
            textSize = 11f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })

        val close = TextView(activity).apply {
            text = "\u2715"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Close video"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#88000000"))
                setStroke(dp(1), Color.parseColor("#88FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                setMargins(dp(12), 0, 0, 0)
            }
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }

        row.addView(texts)
        row.addView(close)
        bar.addView(row)

        if (video.isYouTube) {
            bar.addView(TextView(activity).apply {
                text = "Video not playing? Watch on YouTube \u2197"
                textSize = 11f
                setTextColor(Color.parseColor("#FFB3D4FF"))
                setPadding(0, dp(8), 0, dp(4))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onOpenOnYouTube(video.watchUrl)
                }
            })
        }
        if (!video.isYouTube) {
            bar.addView(TextView(activity).apply {
                text = "Video not playing? Open in browser \u2197"
                textSize = 11f
                setTextColor(Color.parseColor("#FFB3D4FF"))
                setPadding(0, dp(8), 0, dp(4))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onOpenOnYouTube(video.watchUrl)
                }
            })
        }
        return bar
    }

    private fun isVideoFile(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".webm") ||
            path.endsWith(".m3u8") || path.endsWith(".ogg")
    }

    private fun pageFor(video: VideoItem): String {
        val url = video.embedUrl.replace("\"", "%22")
        val isFile = isVideoFile(video.embedUrl)

        val inner = if (isFile) {
            "<video src=\"$url\" controls autoplay playsinline " +
                "style=\"width:100%;height:100%;background:#000\"></video>"
        } else {
            "<iframe src=\"$url\" " +
                "style=\"position:absolute;top:0;left:0;width:100%;height:100%;border:0\" " +
                "allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" " +
                "allowfullscreen referrerpolicy=\"strict-origin-when-cross-origin\"></iframe>"
        }

        return "<!DOCTYPE html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;" +
            "overflow:hidden}iframe,video{position:absolute;top:0;left:0;width:100%;" +
            "height:100%;border:0;background:#000}</style>" +
            "</head><body>$inner</body></html>"
    }

    private fun statusBarHeight(): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
