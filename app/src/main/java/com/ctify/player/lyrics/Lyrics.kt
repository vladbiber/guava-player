package com.ctify.player.lyrics

import com.ctify.player.data.Track
import com.ctify.player.net.Feature
import com.ctify.player.net.NetworkGate
import com.ctify.player.ui.youtube.ytVideoId
import com.ctify.player.youtube.GateDownloader
import com.ctify.player.youtube.YtBrowse
import com.ctify.player.youtube.YtRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

/** Timed lines when the song has them, otherwise the plain text; both null means none were found. [source] names the service. */
data class Lyrics(val synced: List<LyricLine>?, val plain: String?, val source: String? = null) {
    val found: Boolean get() = !synced.isNullOrEmpty() || !plain.isNullOrBlank()

    companion object {
        val NONE = Lyrics(null, null)
    }
}

object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val stamped = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+)(.*)$""")

    /** "[01:02.34][02:10.00] text" lines to time-ordered lines; tags like [ar:...] are dropped. */
    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { line ->
        val m = stamped.find(line.trim()) ?: return@flatMap emptySequence()
        val text = m.groupValues[2].trim()
        stamp.findAll(m.groupValues[1]).map { t ->
            val (min, sec, frac) = t.destructured
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + frac.padEnd(3, '0').toLong(), text)
        }
    }.sortedBy { it.timeMs }.toList()

    /** The line playing at [positionMs]: the last one that has started, or -1 before the first. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }
}

/** Lyrics from LRCLIB, then NetEase, YouTube Music and (opt-in) Musixmatch; kept in memory per track for the session. */
object LyricsRepository {
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private const val UA = "guava-player (github.com/vladbiber/guava-player)"
    private val api = "https://lrclib.net/api/".toHttpUrl()
    private const val MXM = "https://apic-desktop.musixmatch.com/ws/1.1/"
    @Volatile private var mxmToken: String? = null
    // NetEase prefixes its LRC with credit lines like "作词 : name"
    private val credit = Regex("""^[\p{IsHan}\s/]{1,12}[:：]""")

    fun cached(track: Track): Lyrics? = cache[track.id]

    /** First source with synced lyrics wins, else the first with plain; a failed source means nothing is cached. */
    suspend fun get(track: Track): Lyrics {
        cache[track.id]?.let { return it }
        val sec = track.durationMs / 1000
        val sources = listOf<Pair<String, suspend () -> Lyrics?>>(
            "LRCLIB" to {
                lrclib("get", "track_name" to track.title, "artist_name" to track.artist, "album_name" to track.album, "duration" to sec.toString())
                    ?.let { from(JSONObject(it)) }
            },
            "LRCLIB" to { lrclibSearch(sec, "track_name" to track.title, "artist_name" to track.artist) },
            "LRCLIB" to { lrclibLoose(track, sec) },
            "NetEase" to { netease(track, sec) },
            "YouTube Music" to { youtube(track) },
            "Musixmatch" to { if (NetworkGate.musixmatch.value) musixmatch(track, sec) else null },
        )
        var plain: Lyrics? = null
        var failed = false
        for ((name, source) in sources) {
            val l = try { source() } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; null }
            if (l == null || !l.found) continue
            if (!l.synced.isNullOrEmpty()) return l.copy(source = name).also { cache[track.id] = it }
            if (plain == null) plain = l.copy(source = name)
        }
        return (plain ?: Lyrics.NONE).also { if (!failed) cache[track.id] = it }
    }

    private suspend fun lrclib(path: String, vararg q: Pair<String, String>): String? =
        fetch(api.newBuilder().addPathSegment(path).apply { q.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()) {
            header("User-Agent", UA)
        }

    private suspend fun lrclibSearch(sec: Long, vararg q: Pair<String, String>): Lyrics? =
        lrclib("search", *q)?.let { best(JSONArray(it), sec) }

    /** Cleaned guesses the plain search didn't already try, then LRCLIB's free-text search. */
    private suspend fun lrclibLoose(t: Track, sec: Long): Lyrics? {
        val guesses = TitleClean.guesses(t.title, t.artist)
        for ((title, artist) in guesses - (t.title to t.artist)) {
            lrclibSearch(sec, "track_name" to title, "artist_name" to artist)?.takeIf { it.found }?.let { return it }
        }
        val (title, artist) = guesses.firstOrNull() ?: return null
        return lrclibSearch(sec, "q" to "$artist $title")
    }

    private suspend fun netease(t: Track, sec: Long): Lyrics? {
        val (title, artist) = TitleClean.guesses(t.title, t.artist).firstOrNull() ?: return null
        val url = "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
            .addQueryParameter("s", "$artist $title").addQueryParameter("type", "1").addQueryParameter("limit", "5").build()
        val songs = JSONObject(fetch(url.toString()) ?: return null).optJSONObject("result")?.optJSONArray("songs") ?: return null
        val all = (0 until songs.length()).map { songs.getJSONObject(it) }
        val named = all.filter { TitleClean.key(it.optString("name")) == TitleClean.key(title) }
        val close = { o: JSONObject -> sec > 0 && abs(o.optLong("duration") / 1000 - sec) <= 5 }
        val song = named.firstOrNull(close) ?: named.firstOrNull() ?: all.firstOrNull(close) ?: return null
        val lrc = JSONObject(fetch("https://music.163.com/api/song/lyric?id=${song.optLong("id")}&lv=1&tv=-1") ?: return null)
            .optJSONObject("lrc")?.optString("lyric").orEmpty()
        if ("纯音乐" in lrc) return null // "instrumental, please enjoy"
        val synced = Lrc.parse(lrc).filterNot { credit.containsMatchIn(it.text) }.takeIf { l -> l.any { it.text.isNotBlank() } }
        return Lyrics(synced, lrc.takeIf { synced == null && it.isNotBlank() && '[' !in it })
    }

    /** Plain lyrics from the video's Lyrics tab on YouTube Music; only for songs that came from YouTube. */
    private suspend fun youtube(t: Track): Lyrics? {
        val id = t.ytVideoId() ?: return null
        val next = ytm("next", JSONObject().put("videoId", id)) ?: return null
        val browseId = Regex("\"(MPLY[\\w-]+)\"").find(next)?.groupValues?.get(1) ?: return null
        val runs = JSONObject(ytm("browse", JSONObject().put("browseId", browseId)) ?: return null)
            .optJSONObject("contents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")?.optJSONObject(0)
            ?.optJSONObject("musicDescriptionShelfRenderer")?.optJSONObject("description")?.optJSONArray("runs") ?: return null
        return Lyrics(null, (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() })
    }

    private suspend fun ytm(endpoint: String, body: JSONObject): String? {
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", YtBrowse.CLIENT_VERSION)
            .put("hl", YtRuntime.hl).put("gl", YtRuntime.gl)
        body.put("context", JSONObject().put("client", client))
        return fetch("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false") {
            post(body.toString().toRequestBody("application/json".toMediaType()))
            header("Origin", "https://music.youtube.com")
            header("Referer", "https://music.youtube.com/")
            header("User-Agent", GateDownloader.WEB_USER_AGENT)
            header("X-Youtube-Client-Name", "67")
            header("X-Youtube-Client-Version", YtBrowse.CLIENT_VERSION)
        }
    }

    /** Musixmatch's unofficial desktop API; it may stop working at any time, hence opt-in. */
    private suspend fun musixmatch(t: Track, sec: Long): Lyrics? {
        val token = mxmToken ?: JSONObject(mxm("token.get") ?: return null).optJSONObject("message")?.optJSONObject("body")
            ?.optString("user_token")
            // an all-zero token is the anti-bot decoy, its lyrics are placeholder gibberish
            ?.takeIf { tok -> tok.isNotBlank() && tok.any { it != '0' } }?.also { mxmToken = it } ?: return null
        val (title, artist) = TitleClean.guesses(t.title, t.artist).firstOrNull() ?: return null
        val msg = JSONObject(
            mxm(
                "macro.subtitles.get", "q_track" to title, "q_artist" to artist, "usertoken" to token,
                "namespace" to "lyrics_richsynched", "subtitle_format" to "lrc",
                *(if (sec > 0) arrayOf("q_duration" to sec.toString()) else emptyArray()),
            ) ?: return null
        ).optJSONObject("message")
        if (msg?.optJSONObject("header")?.optInt("status_code") == 401) mxmToken = null
        val calls = msg?.optJSONObject("body")?.optJSONObject("macro_calls") ?: return null
        fun body(call: String) = calls.optJSONObject(call)?.optJSONObject("message")?.optJSONObject("body")
        val lrc = body("track.subtitles.get")?.optJSONArray("subtitle_list")?.optJSONObject(0)
            ?.optJSONObject("subtitle")?.optString("subtitle_body").orEmpty()
        val plain = body("track.lyrics.get")?.optJSONObject("lyrics")?.optString("lyrics_body")
            ?.substringBefore("*******")?.trim()?.takeIf { it.isNotBlank() }
        return Lyrics(Lrc.parse(lrc).takeIf { it.isNotEmpty() }, plain)
    }

    private suspend fun mxm(method: String, vararg q: Pair<String, String>): String? {
        val url = (MXM + method).toHttpUrl().newBuilder().addQueryParameter("app_id", "web-desktop-app-v1.0")
            .addQueryParameter("format", "json").apply { q.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return fetch(url.toString()) { header("Cookie", "AWSELBCORS=0; AWSELB=0") }
    }

    /** null on 404; other failures throw. */
    private suspend fun fetch(url: String, build: Request.Builder.() -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val req = NetworkGate.request(url, Feature.LYRICS).apply(build).build()
        NetworkGate.client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> error("${req.url.host} answered ${r.code}")
                else -> r.body?.string()
            }
        }
    }

    private fun from(o: JSONObject): Lyrics {
        if (o.optBoolean("instrumental")) return Lyrics.NONE
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let(Lrc::parse)?.takeIf { it.isNotEmpty() }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return Lyrics(synced, plain)
    }

    /** Synced lyrics of a close length beat any synced, which beat plain. */
    private fun best(a: JSONArray, sec: Long): Lyrics? {
        val all = (0 until a.length()).map { a.getJSONObject(it) }
        val close = all.filter { abs(it.optDouble("duration", 0.0) - sec) <= 5 }
        return (close + all).map(::from).let { l -> l.firstOrNull { it.synced != null } ?: l.firstOrNull { it.found } }
    }
}

