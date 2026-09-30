package com.deeprows.browser

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * A YouTube channel you choose. [id] is the channel id that starts with "UC".
 * [category] becomes a tab in Deeprows Reels (sports, comedy, lifestyle, gist...).
 */
data class VideoChannel(val name: String, val id: String, val category: String = "general")

data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val published: String,
    val category: String = "general",
    /** Set only for your own non-YouTube videos (Vimeo embed, .mp4 link, etc). */
    val customUrl: String? = null
) {
    val isYouTube: Boolean get() = customUrl == null

    val watchUrl: String
        get() = customUrl ?: "https://www.youtube.com/watch?v=$videoId"

    /** Official YouTube embedded player (allowed by YouTube's terms), or your own embed URL. */
    val embedUrl: String
        get() = customUrl
            ?: "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
}

/**
 * Builds the video feed from the public RSS feed of each channel.
 * No API key needed. Channels can be changed from remote/config.json.
 */
object VideoRepository {

    // Used when config.json has no "videoChannels". Change them in config.json.
    private val defaultChannels = listOf(
        VideoChannel("ESPN", "UCiWLfSweyRNmLpgEHekhoAg", "sports"),
        VideoChannel("NBA", "UCWJ2lWNubArHWmf3FIHbfcQ", "sports"),
        VideoChannel("Sky Sports Football", "UCNAf1k0yIjyGu3k9BwAg3lg", "sports"),
        VideoChannel("Red Bull", "UCblfuW_4rakIf2h6aqANefA", "sports"),

        VideoChannel("The Tonight Show", "UC8-Th83bH_thdKZDJCrn88g", "comedy"),
        VideoChannel("Saturday Night Live", "UCqFzWxSCi39LnW1JKFR3efg", "comedy"),
        VideoChannel("Jimmy Kimmel Live", "UCa6vGFO9ty8v5KZJXQxdhaw", "comedy"),
        VideoChannel("The Daily Show", "UCwWhs_6x42TyRM4Wstoq8HA", "comedy"),

        VideoChannel("Tasty", "UCJFp8uSYCjXOMnkUyb3CQ3Q", "lifestyle"),
        VideoChannel("BuzzFeed Video", "UCpko_-a4wgz2u_DgDgd9fqA", "lifestyle"),
        VideoChannel("Good Mythical Morning", "UC4PooiX37Pld1T8J5SYT-SQ", "lifestyle"),

        VideoChannel("Channels Television", "UCEXGDNclvmg6RW0vipJYsTQ", "gist"),
        VideoChannel("BBC News", "UC16niRr50-MSBwiO3YDb3RA", "gist"),
        VideoChannel("Sky News", "UCoMdktPbSTixAyNGwb-UYkQ", "gist"),
        VideoChannel("Al Jazeera English", "UCNye-wNBqNL5ZzHSJj3l8Bg", "gist"),

        VideoChannel("TED", "UCAuUUnT6oDeKwE6v1NGQxug", "learn"),
        VideoChannel("NASA", "UCLA_DiR1FfKNvjuUpBHmylQ", "learn")
    )

    private val channels: List<VideoChannel>
        get() = RemoteConfig.current?.videoChannels ?: defaultChannels

    @Volatile private var cached: List<VideoItem>? = null
    @Volatile private var cachedKey = ""
    @Volatile private var cachedAt = 0L

    private val embedThumbs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Finds the thumbnail a player page provides itself (og:image, twitter:image,
     * poster="..." or a player "image"/"poster" setting). Used for custom videos
     * that have no "thumbnail" in config.json. Returns "" when none is found.
     */
    suspend fun embedThumbnail(pageUrl: String): String =
        embedThumbs[pageUrl] ?: withContext(Dispatchers.IO) {
            val found = findEmbedThumbnail(pageUrl)
            if (found.isNotEmpty()) embedThumbs[pageUrl] = found
            found
        }

    private val thumbPatterns = listOf(
        Regex("<meta[^>]+property=[\"']og:image(?::secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']", RegexOption.IGNORE_CASE),
        Regex("<meta[^>]+name=[\"']twitter:image[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("poster=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("[\"'](?:image|poster|thumbnail|thumb)[\"']\\s*:\\s*[\"']([^\"']+\\.(?:jpe?g|png|webp)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
    )

    private fun findEmbedThumbnail(pageUrl: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(pageUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Referer", "https://deeprows.github.io/")
            if (conn.responseCode != 200) return ""

            // The thumbnail is always near the top of the page; 400 KB is plenty.
            val html = conn.inputStream.bufferedReader().use {
                val buf = CharArray(400_000)
                val n = it.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }

            for (pattern in thumbPatterns) {
                val raw = pattern.find(html)?.groupValues?.get(1) ?: continue
                val clean = raw.replace("\\/", "/").replace("&amp;", "&").trim()
                val absolute = when {
                    clean.startsWith("//") -> "https:$clean"
                    clean.startsWith("http") -> clean
                    clean.startsWith("/") -> {
                        val u = URL(pageUrl)
                        "${u.protocol}://${u.host}$clean"
                    }
                    else -> ""
                }
                if (absolute.startsWith("http")) return absolute
            }
            ""
        } catch (_: Exception) {
            ""
        } finally {
            conn?.disconnect()
        }
    }

    /** Forget the cached feed so the next load fetches fresh videos (pull to refresh). */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    /** Your own hand-picked videos from config.json ("customVideos"). */
    fun customVideos(): List<VideoItem> = RemoteConfig.current?.customVideos ?: emptyList()

    /** Pulls the 11-character video id out of any normal YouTube link. */
    fun youtubeId(url: String): String? {
        val patterns = listOf(
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtube(?:-nocookie)?\\.com/(?:embed|shorts|live)/([A-Za-z0-9_-]{11})")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    /** Tabs to show: your own videos first, then the channel categories in order. */
    fun categories(): List<String> =
        (customVideos().map { it.category } + channels.map { it.category }).distinct()

    fun categoryLabel(category: String): String = when (category.lowercase()) {
        "all" -> "\u2728 For You"
        "deeprows" -> "\u2B50 Deeprows"
        "sports" -> "\u26BD Sports"
        "comedy" -> "\uD83D\uDE02 Comedy"
        "lifestyle" -> "\uD83D\uDC83 Lifestyle"
        "gist" -> "\uD83D\uDD25 Latest Gist"
        "news" -> "\uD83D\uDCF0 News"
        "music" -> "\uD83C\uDFB5 Music"
        "learn" -> "\uD83C\uDF93 Learn"
        "tech" -> "\uD83D\uDCBB Tech"
        "general" -> "\uD83C\uDFAC Videos"
        else -> category.replaceFirstChar { it.uppercase() }
    }

    /** Mixes categories one by one so "For You" is not dominated by one channel type. */
    fun interleave(items: List<VideoItem>): List<VideoItem> {
        val groups = categories()
            .map { c -> items.filter { it.category == c } }
            .filter { it.isNotEmpty() }
        val out = mutableListOf<VideoItem>()
        var i = 0
        while (groups.any { it.size > i }) {
            groups.forEach { g -> if (g.size > i) out.add(g[i]) }
            i++
        }
        return out
    }

    /** Newest videos from all channels. Kept for 10 minutes so the home page can rebuild cheaply. */
    suspend fun getVideos(
        perChannel: Int = 4,
        limit: Int = 80,
        force: Boolean = false
    ): List<VideoItem> {
        val list = channels
        val key = list.joinToString(",") { it.id }
        val now = System.currentTimeMillis()
        val saved = cached
        if (!force && saved != null && key == cachedKey && now - cachedAt < 10 * 60_000L) {
            return saved
        }

        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                list
                    .map { channel -> async { fetchChannel(channel, perChannel) } }
                    .awaitAll()
            }
                .flatten()
                .distinctBy { it.videoId }
                .sortedByDescending { it.published }
                .take(limit)
        }
        if (result.isNotEmpty()) {
            cached = result
            cachedKey = key
            cachedAt = now
        }
        return result
    }

    private fun fetchChannel(channel: VideoChannel, max: Int): List<VideoItem> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("https://www.youtube.com/feeds/videos.xml?channel_id=${channel.id}")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DeeprowsBrowser")
            if (conn.responseCode != 200) return emptyList()

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(conn.inputStream, null)

            val items = mutableListOf<VideoItem>()
            var inEntry = false
            var inAuthor = false
            var id = ""
            var title = ""
            var published = ""
            var thumb = ""
            var author = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && items.size < max) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            id = ""; title = ""; published = ""; thumb = ""; author = ""
                        }
                        "author" -> inAuthor = true
                        "videoId" -> if (inEntry) id = parser.nextText().trim()
                        "title" -> if (inEntry && title.isEmpty()) title = parser.nextText().trim()
                        "published" -> if (inEntry) published = parser.nextText().trim()
                        "name" -> if (inEntry && inAuthor) author = parser.nextText().trim()
                        "thumbnail" -> if (inEntry && thumb.isEmpty()) {
                            thumb = parser.getAttributeValue(null, "url") ?: ""
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "author" -> inAuthor = false
                        "entry" -> {
                            if (id.isNotEmpty() && title.isNotEmpty()) {
                                items.add(
                                    VideoItem(
                                        videoId = id,
                                        title = title,
                                        channel = author.ifEmpty { channel.name },
                                        thumbnail = thumb.ifEmpty {
                                            "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                                        },
                                        published = published,
                                        category = channel.category
                                    )
                                )
                            }
                            inEntry = false
                        }
                    }
                }
                event = parser.next()
            }
            items
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }
}
