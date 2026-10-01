package com.deeprows.browser

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.net.HttpURLConnection
import java.net.URL

/**
 * A YouTube channel you choose. [id] is the channel id that starts with "UC".
 * [category] becomes a tab in Deeprows Reels (sports, comedy, lifestyle, gist...).
 */
data class VideoChannel(val name: String, val id: String, val category: String = "general")

/**
 * A non-YouTube place to pull reels from (config.json -> "videoSources").
 * [kind] is "dailymotion", "peertube" or "rss".
 *  - dailymotion: [id] = a Dailymotion username, or [channel] = a topic channel such as "sport" or "news"
 *  - peertube:    [host] = the instance (e.g. "tilvids.com"), [id] = optional channel name
 *  - youtube:     [id] = a YouTube channel id starting with "UC" (used for regional lists)
 *  - rss:         [url] = any https video feed (RSS / Atom / Media RSS with .mp4 or .m3u8 links)
 */
data class VideoSource(
    val kind: String,
    val name: String,
    val category: String = "general",
    val id: String = "",
    val channel: String = "",
    val host: String = "",
    val url: String = ""
) {
    val key: String get() = "$kind|$id|$channel|$host|$url"
}

data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val published: String,
    val category: String = "general",
    /** Set only for your own non-YouTube videos (Vimeo embed, .mp4 link, etc). */
    val customUrl: String? = null,
    /** "sport" or "movie" (from config.json). Picks the fallback card picture. */
    val type: String = "",
    /** Normal web page of the video, used by "Open in browser" (embed links often say Forbidden). */
    val pageUrl: String? = null
) {
    val isYouTube: Boolean get() = customUrl == null

    val watchUrl: String
        get() = pageUrl ?: customUrl ?: "https://www.youtube.com/watch?v=$videoId"

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

    /** Two-letter country of the user (SIM / network, then phone language). Set by the Reels section. */
    @Volatile var userCountry: String = ""

    /** Videos picked for the user's country (config.json -> "regionalVideos"), shown as "Near You". */
    private val localSources: List<VideoSource>
        get() = RemoteConfig.current?.regionalVideos?.get(userCountry.uppercase()) ?: emptyList()

    private val sources: List<VideoSource>
        get() = (RemoteConfig.current?.videoSources ?: emptyList()) + localSources

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
        (customVideos().map { it.category } + localSources.map { it.category } +
            channels.map { it.category } + sources.map { it.category }).distinct()

    fun categoryLabel(category: String): String = when (category.lowercase()) {
        "all" -> "\u2728 For You"
        "local" -> localLabel()
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

    private fun localLabel(): String {
        val code = userCountry.uppercase()
        if (code.length != 2) return "\uD83D\uDCCD Near You"
        val flag = String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
        val name = Locale("", code).getDisplayCountry(Locale.ENGLISH)
        return if (name.isBlank() || name == code) "$flag Near You" else "$flag $name"
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
        limit: Int = 120,
        force: Boolean = false
    ): List<VideoItem> {
        val list = channels
        val srcList = sources
        val key = list.joinToString(",") { it.id } + "#" + srcList.joinToString(",") { it.key }
        val now = System.currentTimeMillis()
        val saved = cached
        if (!force && saved != null && key == cachedKey && now - cachedAt < 10 * 60_000L) {
            return saved
        }

        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                list.map { channel -> async { fetchChannel(channel, perChannel) } }
                    .plus(srcList.map { src -> async { fetchSource(src, perChannel) } })
                    .awaitAll()
            }
                .flatten()
                // Near You wins when the same video is also in a general channel.
                .sortedByDescending { it.category == "local" }
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

    // ---------------------------------------------------------
    // Other video sources (Dailymotion, PeerTube, any video RSS feed)
    // ---------------------------------------------------------

    private suspend fun fetchSource(src: VideoSource, max: Int): List<VideoItem> = try {
        when (src.kind) {
            "youtube" -> fetchChannel(VideoChannel(src.name, src.id, src.category), max)
            "dailymotion" -> fetchDailymotion(src, max)
            "peertube" -> fetchPeerTube(src, max)
            "rss" -> fetchRss(src, max)
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DeeprowsBrowser")
            conn.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private val isoOut = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun isoFromMillis(ms: Long): String = isoOut.get()!!.format(java.util.Date(ms))

    /** Turns ISO or RSS dates into the one format the cards understand. "" if unreadable. */
    private fun normalizeDate(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz"
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                val date = fmt.parse(text) ?: continue
                return isoFromMillis(date.time)
            } catch (_: Exception) {
            }
        }
        return ""
    }

    /**
     * Dailymotion only plays a video inside our app when its owner allows embedding on our
     * domain. Asks Dailymotion's player endpoint first and drops videos it refuses.
     * If the check itself cannot be read, the video is kept.
     */
    private fun dailymotionPlayable(id: String): Boolean {
        val body = httpGet(
            "https://www.dailymotion.com/player/metadata/video/$id?embedder=" +
                java.net.URLEncoder.encode("https://deeprows.github.io/", "UTF-8")
        ) ?: return true
        return try {
            !JSONObject(body).has("error")
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun fetchDailymotion(src: VideoSource, max: Int): List<VideoItem> {
        val base = when {
            src.id.isNotBlank() -> "https://api.dailymotion.com/user/${src.id}/videos"
            src.channel.isNotBlank() -> "https://api.dailymotion.com/channel/${src.channel}/videos"
            else -> return emptyList()
        }
        // Ask for extra, because some will be dropped as not embeddable.
        val body = httpGet(
            "$base?limit=${max * 3}&sort=recent&fields=id,title,thumbnail_480_url,created_time," +
                "owner.screenname,allow_embed"
        ) ?: return emptyList()
        val list = JSONObject(body).optJSONArray("list") ?: return emptyList()
        val candidates = (0 until list.length()).mapNotNull { i ->
            val v = list.optJSONObject(i) ?: return@mapNotNull null
            val id = v.optString("id")
            val title = v.optString("title")
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            if (!v.optBoolean("allow_embed", true)) return@mapNotNull null
            VideoItem(
                videoId = "dm-$id",
                title = title,
                channel = v.optString("owner.screenname").ifEmpty { src.name },
                thumbnail = v.optString("thumbnail_480_url"),
                published = isoFromMillis(v.optLong("created_time") * 1000L),
                category = src.category,
                customUrl = "https://geo.dailymotion.com/player.html?video=$id",
                pageUrl = "https://www.dailymotion.com/video/$id"
            )
        }
        return coroutineScope {
            candidates
                .map { item -> async { item to dailymotionPlayable(item.videoId.removePrefix("dm-")) } }
                .awaitAll()
        }.filter { it.second }.map { it.first }.take(max)
    }

    private fun fetchPeerTube(src: VideoSource, max: Int): List<VideoItem> {
        val host = src.host.removePrefix("https://").trimEnd('/')
        if (host.isEmpty()) return emptyList()
        val path = if (src.id.isNotBlank()) "/api/v1/video-channels/${src.id}/videos"
        else "/api/v1/videos"
        val body = httpGet("https://$host$path?count=$max&sort=-publishedAt&nsfw=false")
            ?: return emptyList()
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val v = data.optJSONObject(i) ?: return@mapNotNull null
            val uuid = v.optString("uuid")
            val title = v.optString("name")
            if (uuid.isEmpty() || title.isEmpty() || v.optBoolean("isLive", false)) {
                return@mapNotNull null
            }
            val thumbPath = v.optString("previewPath").ifEmpty { v.optString("thumbnailPath") }
            val embedPath = v.optString("embedPath").ifEmpty { "/videos/embed/$uuid" }
            VideoItem(
                videoId = "pt-$uuid",
                title = title,
                channel = v.optJSONObject("channel")?.optString("displayName")
                    .orEmpty().ifEmpty { src.name },
                thumbnail = if (thumbPath.isEmpty()) "" else "https://$host$thumbPath",
                published = normalizeDate(v.optString("publishedAt").take(19) + "+00:00"),
                category = src.category,
                customUrl = "https://$host$embedPath?autoplay=1",
                pageUrl = "https://$host/w/$uuid"
            )
        }
    }

    private fun looksLikeVideo(url: String, type: String, medium: String): Boolean {
        if (!url.startsWith("https://")) return false
        val clean = url.substringBefore('?').lowercase()
        return type.startsWith("video/") || medium == "video" ||
            clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".m3u8")
    }

    /** Video links in an article page URL that we know how to play. */
    private fun embedFromPage(link: String): String? {
        Regex("vimeo\\.com/(?:.*/)?(\\d{6,})").find(link)?.let {
            return "https://player.vimeo.com/video/${it.groupValues[1]}"
        }
        Regex("dailymotion\\.com/video/([A-Za-z0-9]+)").find(link)?.let {
            return "https://geo.dailymotion.com/player.html?video=${it.groupValues[1]}"
        }
        return null
    }

    /** Generic video feed: RSS, Atom or Media RSS with mp4 / m3u8 / YouTube / Vimeo / Dailymotion links. */
    private fun fetchRss(src: VideoSource, max: Int): List<VideoItem> {
        if (!src.url.startsWith("https://")) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(src.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DeeprowsBrowser")
            if (conn.responseCode != 200) return emptyList()

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(conn.inputStream, null)

            val items = mutableListOf<VideoItem>()
            var inItem = false
            var title = ""
            var link = ""
            var date = ""
            var videoUrl = ""
            var thumb = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && items.size < max) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        if (name == "item" || name == "entry") {
                            inItem = true
                            title = ""; link = ""; date = ""; videoUrl = ""; thumb = ""
                        } else if (inItem) when (name) {
                            "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (href != null) {
                                    val rel = parser.getAttributeValue(null, "rel")
                                    if (link.isEmpty() && (rel == null || rel == "alternate")) link = href
                                } else if (link.isEmpty()) {
                                    link = parser.nextText().trim()
                                }
                            }
                            "pubDate", "published", "updated", "date" ->
                                if (date.isEmpty()) date = parser.nextText().trim()
                            "enclosure", "content" -> {
                                val url = parser.getAttributeValue(null, "url") ?: ""
                                val type = (parser.getAttributeValue(null, "type") ?: "").lowercase()
                                val medium = (parser.getAttributeValue(null, "medium") ?: "").lowercase()
                                if (videoUrl.isEmpty() && looksLikeVideo(url, type, medium)) videoUrl = url
                            }
                            "thumbnail" -> if (thumb.isEmpty()) {
                                thumb = parser.getAttributeValue(null, "url") ?: ""
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "item" || parser.name == "entry") {
                        inItem = false
                        if (title.isNotEmpty()) {
                            val published = normalizeDate(date)
                            val ytId = youtubeId(link)
                            val embed = when {
                                videoUrl.isNotEmpty() -> videoUrl
                                else -> embedFromPage(link)
                            }
                            if (ytId != null && videoUrl.isEmpty()) {
                                items.add(
                                    VideoItem(
                                        videoId = ytId, title = title, channel = src.name,
                                        thumbnail = thumb.ifEmpty { "https://i.ytimg.com/vi/$ytId/hqdefault.jpg" },
                                        published = published, category = src.category
                                    )
                                )
                            } else if (embed != null) {
                                items.add(
                                    VideoItem(
                                        videoId = "feed-" + embed.hashCode(), title = title,
                                        channel = src.name, thumbnail = thumb,
                                        published = published, category = src.category,
                                        customUrl = embed,
                                        pageUrl = if (videoUrl.isEmpty()) link.ifEmpty { null } else null
                                    )
                                )
                            }
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
