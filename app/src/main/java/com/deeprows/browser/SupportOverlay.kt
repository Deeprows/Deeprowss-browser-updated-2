package com.deeprows.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// =============================================================
// "KINDLY SUPPORT" OVERLAY
// =============================================================
//
// - Appears once, one minute after the app has fully loaded.
// - Shown at most once every 24 hours.
// - "Click Here" opens the support link in an in-app window that
//   closes by itself after AD_WINDOW_SECONDS seconds.
// - There is no close button, and the Back button is ignored while
//   the card or the support window is showing.
//
// The timing and link can be changed in the constants below.
//
// =============================================================

class SupportOverlay(
    private val activity: Activity,
    private val onStateChanged: () -> Unit
) {

    companion object {

        const val AD_URL =
            "https://www.profitableratecpmnetwork.com/iqv44jk21" +
                "?key=c2752cc0c9c553ac66e4fb16cdb95f60"

        // Delay after the app has loaded before the card appears.
        const val SHOW_DELAY_MS = 60_000L

        // The card appears again only after this much time.
        const val REPEAT_MS = 24L * 60L * 60L * 1000L

        // The support window closes itself after this many seconds.
        const val AD_WINDOW_SECONDS = 10

        // If the support page has not finished loading after this long,
        // the countdown starts anyway so the window can never get stuck.
        const val LOAD_TIMEOUT_MS = 20_000L

        private const val PREFS = "deeprows_browser"
        private const val KEY_LAST_SHOWN = "support_overlay_last_shown"

        fun isDue(context: Context): Boolean {

            val last = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SHOWN, 0L)

            val elapsed = System.currentTimeMillis() - last

            // A negative value means the phone clock was moved back.
            return elapsed < 0 || elapsed >= REPEAT_MS
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var overlay: FrameLayout? = null
    private var adWindow: FrameLayout? = null
    private var adWebView: WebView? = null
    private var countdownLabel: TextView? = null
    private var countdownStarted = false

    fun isActive(): Boolean = overlay != null || adWindow != null

    // Called when the phone's Back button is pressed. While the card
    // or the support window is showing, Back is consumed (returns true)
    // but closes nothing.
    fun handleBack(): Boolean = overlay != null || adWindow != null

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun rounded(
        fill: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    // =========================================================
    // SUPPORT CARD
    // =========================================================

    fun show() {

        if (overlay != null || adWindow != null) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: return

        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SHOWN, System.currentTimeMillis())
            .apply()

        val layer = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#E6060A12"))
            isClickable = true
            isFocusable = true
            alpha = 0f
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(20))
        }

        // ---------------- Card 1: support message + button ------

        val card1 = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(
                Color.parseColor("#172033"),
                28,
                Color.parseColor("#2B3A55")
            )
        }

        card1.addView(
            TextView(activity).apply {
                text = "Kindly Support"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
            }
        )

        card1.addView(
            TextView(activity).apply {
                text = "Help us keep Deeprowss running"
                textSize = 14f
                setTextColor(Color.parseColor("#9AA7BC"))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
        )

        card1.addView(
            TextView(activity).apply {
                text = "Wait time: 10s  \u2022  Trigger every 24h  \u2022  Auto close ON"
                textSize = 11f
                setTextColor(Color.parseColor("#6B7A90"))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(12))
            }
        )

        val button = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#0F8F94"),
                    Color.parseColor("#4CCBFF")
                )
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openAdWindow() }
        }

        button.addView(
            TextView(activity).apply {
                text = "Click Here"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
        )

        button.addView(
            TextView(activity).apply {
                text = "Open 1 AD per 24hrs"
                textSize = 12f
                setTextColor(Color.parseColor("#0B1220"))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
        )

        card1.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        column.addView(
            card1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ---------------- Card 2: instructions ------------------

        val card2 = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(6))
            background = rounded(
                Color.parseColor("#172033"),
                28,
                Color.parseColor("#2B3A55")
            )
        }

        card2.addView(
            TextView(activity).apply {
                text = "Instructions \u2013"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(2), 0, 0, dp(8))
            }
        )

        val steps = listOf(
            "Click the button above \u261D\uFE0F",
            "Wait for page to load \uD83E\uDD71",
            "Stay on the page for 10 seconds \u2764\uFE0F",
            "It will close automatically \u263A\uFE0F",
            "Enjoy Deeprowss Browser \uD83D\uDCFA"
        )

        steps.forEachIndexed { index, step ->

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = rounded(
                    Color.parseColor("#0F1626"),
                    14,
                    Color.parseColor("#26344D")
                )
            }

            row.addView(
                TextView(activity).apply {
                    text = "${index + 1}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#2DD4BF"))
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#10222B"))
                        setStroke(dp(1), Color.parseColor("#2DD4BF"))
                    }
                },
                LinearLayout.LayoutParams(dp(24), dp(24))
            )

            row.addView(
                TextView(activity).apply {
                    text = step
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), 0, 0, 0)
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            card2.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(6)) }
            )
        }

        column.addView(
            card2,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
        )

        scroll.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layer.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            layer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        layer.animate().alpha(1f).setDuration(250).start()

        overlay = layer

        onStateChanged()
    }

    fun dismiss() {

        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }

        overlay = null

        onStateChanged()
    }

    // =========================================================
    // SUPPORT WINDOW (closes by itself)
    // =========================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun openAdWindow() {

        dismiss()

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: return

        val layer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#101827"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val label = TextView(activity).apply {
            text = "Loading\u2026 please wait"
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        bar.addView(
            label,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Ignore the blank page used when the window closes.
                    if (url != null && url != "about:blank") {
                        startCountdown()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    // If the main page fails, start the countdown anyway so
                    // the window can never get stuck (it has no close button).
                    if (request?.isForMainFrame == true) {
                        startCountdown()
                    }
                }
            }
            loadUrl(AD_URL)
        }

        column.addView(bar)

        column.addView(
            web,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        layer.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            layer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        adWindow = layer
        adWebView = web

        onStateChanged()

        countdownStarted = false
        countdownLabel = label

        // Safety net: if the page never finishes loading, begin the
        // countdown after LOAD_TIMEOUT_MS so the window still closes.
        handler.postDelayed({ startCountdown() }, LOAD_TIMEOUT_MS)
    }

    // Starts the closing countdown. Runs only once, and only after the
    // support URL has fully loaded (or the load timeout is reached).
    private fun startCountdown() {

        if (countdownStarted || adWindow == null) return
        countdownStarted = true

        val label = countdownLabel ?: return
        var secondsLeft = AD_WINDOW_SECONDS

        label.text = "Thank you for your support \u2022 closing in $secondsLeft s"

        val tick = object : Runnable {
            override fun run() {
                secondsLeft--
                if (secondsLeft <= 0) {
                    closeAdWindow()
                } else {
                    label.text =
                        "Thank you for your support \u2022 closing in $secondsLeft s"
                    handler.postDelayed(this, 1000L)
                }
            }
        }

        handler.postDelayed(tick, 1000L)
    }

    private fun closeAdWindow() {

        handler.removeCallbacksAndMessages(null)

        adWebView?.let { web ->
            web.stopLoading()
            web.loadUrl("about:blank")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }

        adWebView = null
        countdownLabel = null
        countdownStarted = false

        adWindow?.let { (it.parent as? ViewGroup)?.removeView(it) }

        adWindow = null

        onStateChanged()
    }

    fun destroy() {
        closeAdWindow()
        dismiss()
    }
}
