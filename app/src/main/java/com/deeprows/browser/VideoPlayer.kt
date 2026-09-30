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

    @SuppressLint("SetJavaScriptEnabled")
    fun show(video: VideoItem, onOpenOnYouTube: (String) -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }

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
        root.addView(buildTopBar(video, dialog, onOpenOnYouTube))

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
            root.removeView(web)
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }

        // The base URL tells YouTube which site is embedding the player.
        web.loadDataWithBaseURL(
            "https://deeprows.github.io/",
            pageFor(video),
            "text/html",
            "utf-8",
            null
        )

        dialog.show()
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
        onOpenOnYouTube: (String) -> Unit
    ): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), statusBarHeight() + dp(8), dp(12), dp(22))
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
            text = video.channel
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
        return bar
    }

    private fun pageFor(video: VideoItem): String {
        val url = video.embedUrl.replace("\"", "%22")
        val path = video.embedUrl.substringBefore('?').lowercase()
        val isFile = path.endsWith(".mp4") || path.endsWith(".webm") ||
            path.endsWith(".m3u8") || path.endsWith(".ogg")

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
            "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}</style>" +
            "</head><body>$inner</body></html>"
    }

    private fun statusBarHeight(): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
