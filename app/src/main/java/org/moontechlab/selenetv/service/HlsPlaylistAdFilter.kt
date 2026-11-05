package org.moontechlab.selenetv.service

/**
 * Filters HLS media-playlist segments by URI keywords, following the MoonTVPlus
 * approach. Only EXTINF + its following segment URI are removed; master-playlist
 * variants and unrelated tags are left untouched.
 */
object HlsPlaylistAdFilter {
    val defaultKeywords = listOf(
        "sponsor",
        "/ad/",
        "/ads/",
        "advert",
        "advertisement",
        "/adjump",
        "redtraffic",
    )

    fun filter(body: String, keywords: List<String>): String {
        if (!body.lineSequence().any { it.trim() == "#EXTM3U" }) return body
        if (keywords.none { it.isNotBlank() }) return body

        val lines = body.lines()
        val output = ArrayList<String>(lines.size)
        var index = 0
        var removedAny = false

        while (index < lines.size) {
            val line = lines[index]
            if (line.trim().startsWith("#EXTINF:", ignoreCase = true)) {
                var uriIndex = index + 1
                while (uriIndex < lines.size &&
                    (lines[uriIndex].isBlank() || lines[uriIndex].trimStart().startsWith("#"))
                ) {
                    uriIndex++
                }
                val uri = lines.getOrNull(uriIndex)?.trim().orEmpty()
                val isAd = uri.isNotEmpty() && HlsAdRuleEngine.isAdUri(uri, keywords)
                if (isAd) {
                    index = uriIndex + 1
                    removedAny = true
                    continue
                }
            }
            output += line
            index++
        }

        if (!removedAny) return body

        // CUE/SCTE markers describe the original unfiltered timeline. Once URI
        // filtering changes that timeline, remove those markers to prevent a
        // second, now-misaligned time-based seek.
        return output.filterNot { line ->
            val tag = line.trim().uppercase()
            tag.startsWith("#EXT-X-CUE-OUT") ||
                tag.startsWith("#EXT-X-CUE-IN") ||
                tag.startsWith("#EXT-X-SCTE35-OUT") ||
                tag.startsWith("#EXT-X-SCTE35-IN")
        }.joinToString("\n")
    }
}
