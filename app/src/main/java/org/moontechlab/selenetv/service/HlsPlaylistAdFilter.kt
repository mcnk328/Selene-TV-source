package org.moontechlab.selenetv.service

/**
 * Filters HLS media-playlist segments by explicit URI keywords. An optional,
 * separately controlled VOD structure pass can remove isolated short
 * discontinuity groups; live and unsupported playlists are left unchanged.
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

    fun filter(
        body: String,
        keywords: List<String>,
        enableStructuralFilter: Boolean = false,
    ): String {
        if (!body.lineSequence().any { it.trim() == "#EXTM3U" }) return body
        var result = body
        if (keywords.any { it.isNotBlank() }) {
            val lines = result.lines()
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
                    if (uri.isNotEmpty() && HlsAdRuleEngine.isAdUri(uri, keywords)) {
                        index = uriIndex + 1
                        removedAny = true
                        continue
                    }
                }
                output += line
                index++
            }

            if (removedAny) {
                // CUE/SCTE markers describe the original timeline. Once URI
                // filtering changes it, discard markers that could seek wrongly.
                result = output.filterNot { line ->
                    val tag = line.trim().uppercase()
                    tag.startsWith("#EXT-X-CUE-OUT") ||
                        tag.startsWith("#EXT-X-CUE-IN") ||
                        tag.startsWith("#EXT-X-SCTE35-OUT") ||
                        tag.startsWith("#EXT-X-SCTE35-IN")
                }.joinToString("\n")
            }
        }

        return if (enableStructuralFilter) HlsVODStructureFilter.filter(result) else result
    }
}
