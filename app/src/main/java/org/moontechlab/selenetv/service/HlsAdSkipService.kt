package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URL

/**
 * Best-effort HLS ad marker reader.
 *
 * Explicit SCTE-35/CUE markers and configured segment-URI keywords are recognized.
 * It deliberately does not infer ads from segment length or video content.
 */
object HlsAdSkipService {
    data class AdSegment(val startMs: Long, val endMs: Long)

    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_USER_AGENT = "SeleneTV/1.0"

    internal fun parseMediaPlaylist(
        body: String,
        keywords: List<String> = HlsPlaylistAdFilter.defaultKeywords,
    ): List<AdSegment> {
        if (!body.lineSequence().any { it.trim() == "#EXTM3U" }) return emptyList()

        val result = mutableListOf<AdSegment>()
        var timelineMs = 0L
        var nextDurationMs: Long? = null
        var adStartMs: Long? = null
        var declaredAdDurationMs: Long? = null

        fun closeAd(endMs: Long) {
            val start = adStartMs ?: return
            val end = endMs.coerceAtLeast(start)
            if (end - start >= 1_000L) result += AdSegment(start, end)
            adStartMs = null
            declaredAdDurationMs = null
        }

        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    nextDurationMs = seconds?.takeIf { it > 0.0 }?.let { (it * 1000.0).toLong() }
                }
                (line.startsWith("#EXT-X-CUE-OUT:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-CUE-OUT=", ignoreCase = true) ||
                    line.equals("#EXT-X-CUE-OUT", ignoreCase = true)) ||
                    line.startsWith("#EXT-X-SCTE35-OUT:", ignoreCase = true) -> {
                    if (adStartMs == null) adStartMs = timelineMs
                    if (line.startsWith("#EXT-X-CUE-OUT", ignoreCase = true)) {
                        val value = line.substringAfter(':', line.substringAfter('=', "")).trim()
                        val durationText = Regex("DURATION=([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                            .find(value)?.groupValues?.getOrNull(1)
                        val duration = durationText?.toDoubleOrNull() ?: value.substringBefore(',').toDoubleOrNull()
                        if (duration != null && duration > 0) declaredAdDurationMs = (duration * 1000.0).toLong()
                    }
                }
                line.startsWith("#EXT-X-CUE-IN", ignoreCase = true) ||
                    line.startsWith("#EXT-X-SCTE35-IN", ignoreCase = true) -> closeAd(timelineMs)
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val duration = nextDurationMs ?: 0L
                    val isKeywordAd = keywords.any { keyword ->
                        keyword.isNotBlank() && line.contains(keyword.trim(), ignoreCase = true)
                    }
                    if (isKeywordAd && duration >= 1_000L) {
                        result += AdSegment(timelineMs, timelineMs + duration)
                    }
                    timelineMs += duration
                    nextDurationMs = null
                    val start = adStartMs
                    val declared = declaredAdDurationMs
                    if (start != null && declared != null && timelineMs >= start + declared) {
                        closeAd(start + declared)
                    }
                }
            }
        }

        if (adStartMs != null) {
            val start = adStartMs!!
            declaredAdDurationMs?.let { closeAd(start + it) }
        }
        if (result.isEmpty()) return emptyList()
        val merged = mutableListOf<AdSegment>()
        result.sortedBy { it.startMs }.forEach { segment ->
            val previous = merged.lastOrNull()
            if (previous != null && segment.startMs <= previous.endMs) {
                merged[merged.lastIndex] = AdSegment(previous.startMs, maxOf(previous.endMs, segment.endMs))
            } else {
                merged += segment
            }
        }
        return merged
    }

    /** Fetch a playlist and follow a master playlist's first variant, if present. */
    suspend fun fetchAdSegments(url: String, userAgent: String? = null): List<AdSegment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bodyAndUrl = fetchText(url, userAgent)
                val playlist = bodyAndUrl.first
                if (playlist.lineSequence().none { it.trim().startsWith("#EXTINF:") }) {
                    val variant = playlist.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: return@runCatching emptyList()
                    val variantUrl = URL(URL(bodyAndUrl.second), variant).toString()
                    parseMediaPlaylist(fetchText(variantUrl, userAgent).first, AppDataService.getHlsAdSkipKeywordsSync())
                } else {
                    parseMediaPlaylist(playlist, AppDataService.getHlsAdSkipKeywordsSync())
                }
            }.getOrDefault(emptyList())
        }

    private fun fetchText(url: String, userAgent: String?): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)
            .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
            .build()
        MediaHttp.httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HLS playlist request failed: ${response.code}" }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            require(bytes.size <= MAX_PLAYLIST_BYTES) { "HLS playlist is too large" }
            return bytes.toString(Charsets.UTF_8) to (response.request.url.toString())
        }
    }
}
