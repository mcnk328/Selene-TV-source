package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsAdSkipServiceTest {
    @Test
    fun parsesCueOutAndCueInAsTimelineRange() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:10.0,
            content-0.ts
            #EXT-X-CUE-OUT:12
            #EXTINF:6.0,
            ad-0.ts
            #EXTINF:6.0,
            ad-1.ts
            #EXT-X-CUE-IN
            #EXTINF:10.0,
            content-1.ts
        """.trimIndent()

        assertEquals(
            listOf(HlsAdSkipService.AdSegment(10_000L, 22_000L)),
            HlsAdSkipService.parseMediaPlaylist(playlist),
        )
    }

    @Test
    fun closesCueOutWhenDeclaredDurationIsReached() {
        val playlist = """
            #EXTM3U
            #EXT-X-CUE-OUT=8
            #EXTINF:4.0,
            ad-0.ts
            #EXTINF:4.0,
            ad-1.ts
            #EXTINF:10.0,
            content.ts
        """.trimIndent()

        assertEquals(
            listOf(HlsAdSkipService.AdSegment(0L, 8_000L)),
            HlsAdSkipService.parseMediaPlaylist(playlist),
        )
    }

    @Test
    fun ignoresPlaylistsWithoutExplicitAdMarkers() {
        val playlist = """
            #EXTM3U
            #EXTINF:5.0,
            segment-0.ts
            #EXTINF:20.0,
            segment-1.ts
        """.trimIndent()

        assertEquals(emptyList<HlsAdSkipService.AdSegment>(), HlsAdSkipService.parseMediaPlaylist(playlist))
    }

    @Test
    fun rejectsNonPlaylistInput() {
        assertEquals(emptyList<HlsAdSkipService.AdSegment>(), HlsAdSkipService.parseMediaPlaylist("not an m3u8"))
    }
}
