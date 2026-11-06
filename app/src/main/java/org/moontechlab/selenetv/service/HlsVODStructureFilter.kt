package org.moontechlab.selenetv.service

/**
 * Conservative, opt-in VOD-only structural filter.
 *
 * It removes only an isolated, very short discontinuity-delimited group
 * between two substantial groups. Provider names and sequence-number gaps are
 * intentionally not deletion signals. Unsupported playlist features fail open.
 */
internal object HlsVODStructureFilter {
    private data class SegmentRecord(
        val extInfIndex: Int,
        val uriIndex: Int,
        val durationMs: Long,
        val discontinuityIndex: Int?,
    )

    private data class SegmentGroup(val records: List<SegmentRecord>) {
        val durationMs: Long get() = records.sumOf { it.durationMs }
        val startIndex: Int get() = records.first().discontinuityIndex ?: records.first().extInfIndex
        val endIndex: Int get() = records.last().uriIndex
    }

    fun filter(body: String): String {
        val lines = body.lines()
        if (lines.none { it.trim() == "#EXTM3U" }) return body
        if (lines.none { it.trim().equals("#EXT-X-ENDLIST", ignoreCase = true) }) return body

        // Do not rewrite encrypted, byte-ranged, initialization-map or master playlists.
        val unsupported = lines.any {
            val tag = it.trim().uppercase()
            tag.startsWith("#EXT-X-KEY:") ||
                tag.startsWith("#EXT-X-SESSION-KEY:") ||
                tag.startsWith("#EXT-X-MAP:") ||
                tag.startsWith("#EXT-X-BYTERANGE:") ||
                tag.startsWith("#EXT-X-STREAM-INF:") ||
                tag.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
        }
        if (unsupported) return body

        val records = mutableListOf<SegmentRecord>()
        var pendingDuration: Long? = null
        var pendingExtInf = -1
        var discontinuityIndex: Int? = null

        for (i in lines.indices) {
            val line = lines[i].trim()
            when {
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    discontinuityIndex = i
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: return body
                    if (!seconds.isFinite() || seconds <= 0.0) return body
                    pendingDuration = (seconds * 1000.0).toLong()
                    pendingExtInf = i
                }
                line.isNotEmpty() && !line.startsWith("#") && pendingDuration != null -> {
                    records += SegmentRecord(
                        extInfIndex = pendingExtInf,
                        uriIndex = i,
                        durationMs = pendingDuration!!,
                        discontinuityIndex = discontinuityIndex,
                    )
                    pendingDuration = null
                    pendingExtInf = -1
                    discontinuityIndex = null
                }
                line.isNotEmpty() && !line.startsWith("#") -> return body
            }
        }
        if (pendingDuration != null || records.size < 8) return body

        val groups = mutableListOf<MutableList<SegmentRecord>>()
        for (record in records) {
            if (record.discontinuityIndex != null && groups.isNotEmpty()) {
                groups += mutableListOf()
            }
            if (groups.isEmpty()) groups += mutableListOf()
            groups.last() += record
        }
        if (groups.size < 3) return body

        val candidates = groups.mapIndexedNotNull { index, group ->
            if (index == 0 || index == groups.lastIndex) return@mapIndexedNotNull null
            val before = groups[index - 1]
            val after = groups[index + 1]
            val candidateDuration = group.sumOf { it.durationMs }
            val neighborsAreSubstantial =
                before.size >= 3 && before.sumOf { it.durationMs } >= 18_000L &&
                after.size >= 3 && after.sumOf { it.durationMs } >= 18_000L
            val isolatedShortGroup = group.size in 1..2 && candidateDuration in 1..12_000L
            if (neighborsAreSubstantial && isolatedShortGroup) index else null
        }
        if (candidates.isEmpty()) return body

        val removeRanges = candidates.map { index ->
            val group = SegmentGroup(groups[index])
            group.startIndex..group.endIndex
        }
        return lines.filterIndexed { lineIndex, _ ->
            removeRanges.none { lineIndex in it }
        }.joinToString("\n")
    }
}
