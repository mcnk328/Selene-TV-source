package org.moontechlab.selenetv.service

/**
 * Shared, deliberately conservative HLS ad-rule engine.
 *
 * Provider signatures and TS sequence numbers are exposed as diagnostics only:
 * neither a provider match nor a numbering gap is sufficient evidence to delete
 * media. Destructive filtering requires an explicit URI keyword match.
 */
object HlsAdRuleEngine {
    enum class Provider { QUANTUM, FEIFAN, BAOFENG, KUAIKAN, UNKNOWN }

    data class Segment(
        val uri: String,
        val durationMs: Long,
        val provider: Provider,
        val sequenceNumber: Long?,
        val hasDiscontinuityBefore: Boolean,
    )

    data class SequenceAnomaly(
        val segmentIndex: Int,
        val previous: Long,
        val current: Long,
        val next: Long?,
    )

    private val quantumName = Regex("[0-9a-z]{11}(\\d{7})\\.ts", RegexOption.IGNORE_CASE)
    private val feifanName = Regex("(\\d{12})(\\d{0,9})\\.ts", RegexOption.IGNORE_CASE)
    private val kuaikanPath = Regex("/\\d{8}/.{8}/(?:10028kb/)?hls/", RegexOption.IGNORE_CASE)

    fun detectProvider(playlistUrl: String): Provider {
        val value = playlistUrl.lowercase()
        return when {
            "cdnlz" in value || "lz-cdn" in value -> Provider.QUANTUM
            "ffzy" in value -> Provider.FEIFAN
            "bfeng" in value -> Provider.BAOFENG
            "kuaikan" in value -> Provider.KUAIKAN
            else -> Provider.UNKNOWN
        }
    }

    fun isAdUri(uri: String, keywords: List<String>): Boolean {
        val normalized = uri.lowercase()
        return keywords.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .any { normalized.contains(it) }
    }

    fun parseSegments(body: String, playlistUrl: String = ""): List<Segment> {
        if (!body.lineSequence().any { it.trim() == "#EXTM3U" }) return emptyList()
        val provider = detectProvider(playlistUrl)
        val lines = body.lines()
        val result = mutableListOf<Segment>()
        var duration = 0L
        var discontinuity = false
        for (index in lines.indices) {
            val line = lines[index].trim()
            when {
                line.startsWith("#EXTINF:", true) -> {
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    duration = seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: 0L
                }
                line.equals("#EXT-X-DISCONTINUITY", true) -> discontinuity = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    result += Segment(line, duration, provider, extractSequence(line, provider), discontinuity)
                    duration = 0L
                    discontinuity = false
                }
            }
        }
        return result
    }

    /**
     * Reports suspicious numbering transitions for logs/tests. This is never
     * used as a deletion rule: live playlists can legitimately wrap or reset.
     */
    fun findSequenceAnomalies(segments: List<Segment>): List<SequenceAnomaly> {
        val result = mutableListOf<SequenceAnomaly>()
        for (i in 1 until segments.size) {
            val previous = segments[i - 1].sequenceNumber ?: continue
            val current = segments[i].sequenceNumber ?: continue
            val next = segments.getOrNull(i + 1)?.sequenceNumber
            if (current <= previous || (current - previous) > 1L) {
                result += SequenceAnomaly(i, previous, current, next)
            }
        }
        return result
    }

    private fun extractSequence(uri: String, provider: Provider): Long? {
        val path = uri.substringBefore('?').substringBefore('#')
        val file = path.substringAfterLast('/')
        val match = when (provider) {
            Provider.QUANTUM -> quantumName.find(file)
            Provider.FEIFAN, Provider.BAOFENG -> feifanName.find(file)
            Provider.KUAIKAN, Provider.UNKNOWN -> null
        } ?: return null
        val digits = when (provider) {
            Provider.QUANTUM -> match.groupValues.getOrNull(1)
            Provider.FEIFAN, Provider.BAOFENG -> match.groupValues.getOrNull(1)
            else -> null
        }
        return digits?.toLongOrNull()
    }
}
