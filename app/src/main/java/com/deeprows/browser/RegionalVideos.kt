package com.deeprows.browser

import android.content.Context
import org.json.JSONObject

/**
 * Videos picked per country ("Near You"), read from config.json -> "regionalVideos".
 *
 * Reads the config copy that RemoteConfig already saved on the phone, so it works
 * on its own and does not depend on any other class changing.
 */
object RegionalVideos {

    @Volatile
    private var byCountry: Map<String, List<VideoSource>> = emptyMap()

    /** Re-reads the saved config. Cheap; call before loading videos. */
    fun load(context: Context) {
        try {
            val text = context.applicationContext
                .getSharedPreferences("remote_config", Context.MODE_PRIVATE)
                .getString("json", null) ?: return
            val obj = JSONObject(text).optJSONObject("regionalVideos")
            if (obj == null) {
                byCountry = emptyMap()
                return
            }
            val map = mutableMapOf<String, List<VideoSource>>()
            val codes = obj.keys()
            while (codes.hasNext()) {
                val code = codes.next()
                val arr = obj.optJSONArray(code) ?: continue
                val list = mutableListOf<VideoSource>()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val kind = c.optString("kind").lowercase()
                    val src = VideoSource(
                        kind = kind,
                        name = c.optString("name", kind).ifBlank { kind },
                        category = "local",
                        id = c.optString("id").trim(),
                        channel = c.optString("channel").trim(),
                        host = c.optString("host").trim(),
                        url = c.optString("url").trim()
                    )
                    val valid = when (kind) {
                        "youtube" -> src.id.startsWith("UC")
                        "dailymotion" -> src.id.isNotEmpty() || src.channel.isNotEmpty()
                        "peertube" -> src.host.isNotEmpty()
                        "rss" -> src.url.startsWith("https://")
                        else -> false
                    }
                    if (valid) list.add(src)
                }
                if (list.isNotEmpty()) map[code.trim().uppercase()] = list
            }
            byCountry = map
        } catch (_: Exception) {
            // Keep whatever was loaded before.
        }
    }

    fun forCountry(code: String): List<VideoSource> =
        byCountry[code.trim().uppercase()] ?: emptyList()
}
