package com.deeprows.browser

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote control panel for the app.
 *
 * The app downloads a small JSON file (remote/config.json in your GitHub
 * repo) every time it opens, keeps a copy on the phone, and falls back to
 * the built-in defaults if anything is missing or broken. Editing that file
 * on GitHub changes the app for every user, with no new APK.
 *
 * NEVER put secrets in config.json - a public repo means public file.
 */
object RemoteConfig {

    const val CONFIG_URL =
        "https://raw.githubusercontent.com/Deeprows/Deeprowss-browser-updated-2/main/remote/config.json"

    private const val PREFS = "remote_config"
    private const val KEY_JSON = "json"

    data class Announcement(
        val id: String,
        val title: String,
        val message: String,
        val url: String?
    )

    data class Update(
        val latestVersionCode: Long,
        val latestVersionName: String,
        val minVersionCode: Long,
        val apkUrl: String,
        val changelog: String
    )

    data class Config(
        val vpnMessage: String?,
        val announcement: Announcement?,
        val update: Update?,
        val categories: List<HomeCategory>?,
        val newsFeeds: List<String>?,
        val sportFeeds: List<String>?,
        val videoChannels: List<VideoChannel>?,
        val reelsEnabled: Boolean,
        val reelsTitle: String?,
        val customVideos: List<VideoItem>?
    )

    @Volatile
    var current: Config? = null
        private set

    /** Load the last saved copy (instant, works offline). Call in onCreate. */
    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return
        current = parse(saved)
    }

    /** Download the latest file. Runs on a background thread; [onDone] runs on that thread. */
    fun refresh(context: Context, onDone: (Config?) -> Unit) {
        val app = context.applicationContext
        Thread {
            var result: Config? = null
            try {
                val conn = URL(CONFIG_URL + "?t=" + System.currentTimeMillis())
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.useCaches = false
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val parsed = parse(text)
                if (parsed != null) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_JSON, text).apply()
                    current = parsed
                    result = parsed
                }
            } catch (_: Exception) {
                // Offline or file broken: keep whatever we already have.
            }
            onDone(result)
        }.start()
    }

    private fun parse(text: String): Config? = try {
        val root = JSONObject(text)

        val announcement = root.optJSONObject("announcement")?.let {
            val message = it.optString("message")
            if (message.isBlank()) null else Announcement(
                id = it.optString("id", message.hashCode().toString()),
                title = it.optString("title", "Deeprows"),
                message = message,
                url = it.optString("url").ifBlank { null }
            )
        }

        val update = root.optJSONObject("update")?.let {
            Update(
                latestVersionCode = it.optLong("latestVersionCode", 0),
                latestVersionName = it.optString("latestVersionName"),
                minVersionCode = it.optLong("minVersionCode", 0),
                apkUrl = it.optString("apkUrl"),
                changelog = it.optString("changelog")
            )
        }

        val categories = root.optJSONArray("homeCategories")?.let { arr ->
            val list = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val subs = c.optJSONArray("subCategories") ?: return@mapNotNull null
                HomeCategory(
                    c.optString("title"),
                    (0 until subs.length()).mapNotNull { j ->
                        val s = subs.optJSONObject(j) ?: return@mapNotNull null
                        val sites = s.optJSONArray("sites") ?: return@mapNotNull null
                        HomeSubCategory(
                            s.optString("title"),
                            (0 until sites.length()).mapNotNull { k ->
                                val site = sites.optJSONObject(k) ?: return@mapNotNull null
                                val name = site.optString("name")
                                val url = site.optString("url")
                                if (name.isBlank() || !url.startsWith("http")) null
                                else HomeSite(name, url)
                            }
                        )
                    }
                )
            }
            list.ifEmpty { null }
        }

        Config(
            vpnMessage = root.optJSONObject("vpn")?.optString("message")?.ifBlank { null },
            announcement = announcement,
            update = update,
            categories = categories,
            newsFeeds = stringList(root, "newsFeeds"),
            sportFeeds = stringList(root, "sportFeeds"),
            videoChannels = root.optJSONArray("videoChannels")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = c.optString("id")
                    if (!id.startsWith("UC")) null
                    else VideoChannel(
                        c.optString("name", id),
                        id,
                        c.optString("category", "general").ifBlank { "general" }.lowercase()
                    )
                }.ifEmpty { null }
            },
            reelsEnabled = root.optJSONObject("reels")?.optBoolean("enabled", true) ?: true,
            reelsTitle = root.optJSONObject("reels")?.optString("title")?.ifBlank { null },
            customVideos = root.optJSONArray("customVideos")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val v = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = v.optString("url")
                    val title = v.optString("title")
                    if (!url.startsWith("https://") || title.isBlank()) return@mapNotNull null

                    val ytId = VideoRepository.youtubeId(url)
                    VideoItem(
                        videoId = ytId ?: ("custom-" + url.hashCode()),
                        title = title,
                        channel = v.optString("channel", "Deeprows").ifBlank { "Deeprows" },
                        thumbnail = v.optString("thumbnail").ifBlank {
                            if (ytId != null) "https://i.ytimg.com/vi/$ytId/hqdefault.jpg" else ""
                        },
                        published = v.optString("published"),
                        category = v.optString("category", "deeprows")
                            .ifBlank { "deeprows" }.lowercase(),
                        customUrl = if (ytId == null) url else null
                    )
                }.ifEmpty { null }
            }
        )
    } catch (_: Exception) {
        null
    }

    private fun stringList(root: JSONObject, key: String): List<String>? {
        val arr = root.optJSONArray(key) ?: return null
        val list = (0 until arr.length()).map { arr.optString(it) }
            .filter { it.startsWith("http") }
        return list.ifEmpty { null }
    }
}
