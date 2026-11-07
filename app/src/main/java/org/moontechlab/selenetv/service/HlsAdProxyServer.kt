package org.moontechlab.selenetv.service

import android.util.Base64
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import okhttp3.Request
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets

/** Loopback HLS proxy shared by ExoPlayer and libmpv. */
object HlsAdProxyServer {
    private const val TAG = "HlsAdProxy"
    private const val FIRST_PORT = 28467
    private const val PORT_ATTEMPTS = 32
    @Volatile private var engine: ApplicationEngine? = null
    @Volatile private var boundPort: Int = 0

    @Synchronized
    private fun ensureStarted(): Int {
        if (engine != null && boundPort > 0) return boundPort
        for (port in FIRST_PORT until FIRST_PORT + PORT_ATTEMPTS) {
            val available = try { ServerSocket(port).use { true } } catch (_: Exception) { false }
            if (!available) continue
            try {
                val started = embeddedServer(Netty, host = "127.0.0.1", port = port) {
                    routing {
                        get("/hls/{payload}") {
                            val payload = call.parameters["payload"]
                            val decoded = try {
                                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
                            } catch (_: Exception) { "" }
                            val separator = decoded.indexOf('\n')
                            val userAgent = if (separator >= 0) decoded.substring(0, separator) else "Mozilla/5.0"
                            val upstreamUrl = if (separator >= 0) decoded.substring(separator + 1) else decoded
                            if (!upstreamUrl.startsWith("http://") && !upstreamUrl.startsWith("https://")) {
                                call.respondBytes(ByteArray(0), status = HttpStatusCode.BadRequest)
                                return@get
                            }

                            val requestBuilder = Request.Builder().url(upstreamUrl).get()
                                .header("User-Agent", userAgent.ifBlank { "Mozilla/5.0" })
                            call.request.header(HttpHeaders.Range)?.let { requestBuilder.header(HttpHeaders.Range, it) }
                            call.request.header("If-Range")?.let { requestBuilder.header("If-Range", it) }
                            call.request.header(HttpHeaders.Accept)?.let { requestBuilder.header(HttpHeaders.Accept, it) }

                            try {
                                MediaHttp.httpClient.newCall(requestBuilder.build()).execute().use { upstream ->
                                    val body = upstream.body
                                    val bytes = body?.bytes() ?: ByteArray(0)
                                    val contentType = upstream.header(HttpHeaders.ContentType).orEmpty()
                                    val isPlaylist = contentType.contains("mpegurl", true) ||
                                        contentType.contains("m3u", true) ||
                                        bytes.toString(StandardCharsets.UTF_8).trimStart().startsWith("#EXTM3U")

                                    val output = if (isPlaylist) {
                                        val raw = bytes.toString(StandardCharsets.UTF_8)
                                        val filtered = if (AppDataService.isHlsAdFilterEnabledSync()) {
                                            HlsAdPlaylistFilter.filter(raw, AppDataService.getHlsAdKeywordsSync())
                                        } else raw
                                        HlsAdPlaylistFilter.rewriteUris(filtered, upstreamUrl) { childUrl ->
                                            makeProxyUrl(childUrl, userAgent)
                                        }.toByteArray(StandardCharsets.UTF_8)
                                    } else bytes

                                    listOf(HttpHeaders.ContentRange, HttpHeaders.AcceptRanges, HttpHeaders.CacheControl,
                                        HttpHeaders.LastModified, HttpHeaders.ETag).forEach { name ->
                                        upstream.header(name)?.let { call.response.headers.append(name, it) }
                                    }
                                    val responseType = if (isPlaylist) {
                                        ContentType.parse("application/vnd.apple.mpegurl")
                                    } else runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream)
                                    call.respondBytes(output, contentType = responseType,
                                        status = HttpStatusCode.fromValue(upstream.code))
                                }
                            } catch (error: Exception) {
                                Log.w(TAG, "HLS upstream request failed", error)
                                call.respondBytes(ByteArray(0), status = HttpStatusCode.BadGateway)
                            }
                        }
                    }
                }.start(wait = false)
                engine = started
                boundPort = port
                Log.i(TAG, "Local HLS proxy listening on 127.0.0.1:$port")
                return port
            } catch (error: Exception) {
                Log.w(TAG, "Unable to bind local HLS proxy on port $port", error)
            }
        }
        return 0
    }

    /** Wraps an already-resolved URL; an existing external proxy remains the upstream. */
    fun wrap(upstreamUrl: String, userAgent: String = ""): String {
        val port = ensureStarted()
        if (port == 0) return upstreamUrl
        val payload = userAgent + "\n" + upstreamUrl
        val encoded = Base64.encodeToString(payload.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "http://127.0.0.1:$port/hls/$encoded"
    }

    private fun makeProxyUrl(url: String, userAgent: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        return wrap(url, userAgent)
    }
}

/** Conservative rules: explicit cue ranges and URI keywords only; never duration/sequence heuristics. */
internal object HlsAdPlaylistFilter {
    const val DEFAULT_KEYWORDS_TEXT =
        "doubleclick,googleads,adservice,advert,vast,sponsor,pre-roll,mid-roll,promo"

    fun filter(playlist: String, keywordsText: String = DEFAULT_KEYWORDS_TEXT): String {
        val lines = playlist.lines()
        if (lines.none { it.trim() == "#EXT-X-ENDLIST" }) return playlist
        if (lines.any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE", true) }) return playlist

        val keywords = keywordsText.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.length >= 3 }
        val output = mutableListOf<String>()
        val pending = mutableListOf<String>()
        var cueActive = false
        var durationRemaining = 0.0
        var removed = false

        fun durationOfSegment(): Double {
            val extinf = pending.lastOrNull { it.startsWith("#EXTINF:", true) } ?: return 0.0
            return extinf.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-CUE-OUT", true) -> {
                    val duration = trimmed.substringAfter(':', "").substringBefore(',').toDoubleOrNull() ?: 0.0
                    if (duration > 0) durationRemaining = duration else cueActive = true
                    removed = true
                }
                trimmed.startsWith("#EXT-X-CUE-IN", true) -> {
                    cueActive = false
                    durationRemaining = 0.0
                    removed = true
                }
                trimmed.startsWith("#EXT-OATCLS-SCTE35", true) -> removed = true
                trimmed.startsWith("#EXT-X-DATERANGE:", true) && trimmed.contains("SCTE35-OUT", true) -> {
                    val duration = Regex("""DURATION=([0-9.]+)""", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    if (duration > 0) durationRemaining = duration else cueActive = true
                    removed = true
                }
                trimmed.startsWith("#EXT-X-DATERANGE:", true) && trimmed.contains("SCTE35-IN", true) -> {
                    cueActive = false
                    durationRemaining = 0.0
                    removed = true
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                    val segmentDuration = durationOfSegment()
                    val durationAd = durationRemaining > 0.0
                    val keywordAd = keywords.any { trimmed.lowercase().contains(it) }
                    if (cueActive || durationAd || keywordAd) {
                        pending.filterNot(::isSegmentScopedTag).forEach(output::add)
                        removed = true
                    } else {
                        output.addAll(pending)
                        output.add(line)
                    }
                    pending.clear()
                    if (durationAd) durationRemaining = (durationRemaining - segmentDuration).coerceAtLeast(0.0)
                }
                else -> pending.add(line)
            }
        }
        output.addAll(pending)
        return if (removed) output.joinToString("\n") else playlist
    }

    private fun isSegmentScopedTag(line: String): Boolean {
        val tag = line.trim().uppercase()
        return tag.startsWith("#EXTINF:") || tag.startsWith("#EXT-X-BYTERANGE:") ||
            tag.startsWith("#EXT-X-PROGRAM-DATE-TIME:") || tag.startsWith("#EXT-X-DISCONTINUITY") ||
            tag.startsWith("#EXT-X-CUE-") || tag.startsWith("#EXT-OATCLS-SCTE35") ||
            (tag.startsWith("#EXT-X-DATERANGE:") && tag.contains("SCTE35"))
    }

    /** Rewrites both bare segment/child-playlist URIs and URI="..." attributes. */
    fun rewriteUris(playlist: String, baseUrl: String, proxy: (String) -> String): String {
        fun resolve(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty() || value.startsWith("data:", true) ||
                value.startsWith("skd:", true) || value.startsWith("blob:", true)) return raw
            return try {
                val absolute = URI(baseUrl).resolve(value).toString()
                if (absolute.startsWith("http://") || absolute.startsWith("https://")) proxy(absolute) else raw
            } catch (_: Exception) { raw }
        }
        val attributePattern = Regex("""URI="([^"]+)"""")
        return playlist.lines().joinToString("\n") { line ->
            if (line.trim().isEmpty()) line
            else if (line.trimStart().startsWith("#")) {
                attributePattern.replace(line) { match -> """URI="${resolve(match.groupValues[1])}"""" }
            } else resolve(line)
        }
    }
}
