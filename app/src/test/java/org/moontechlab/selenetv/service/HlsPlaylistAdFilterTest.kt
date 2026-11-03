package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistAdFilterTest {
    @Test
    fun removesAdSegmentAndKeepsNormalSegments() {
        val playlist = """
            #EXTM3U
            #EXTINF:5.0,
            content-01.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:6.0,
            https://cdn.example/ads/preroll.ts?token=abc
            #EXTINF:5.0,
            content-02.ts
        """.trimIndent()

        val result = HlsPlaylistAdFilter.filter(playlist, listOf("/ads/"))

        assertTrue(result.contains("content-01.ts"))
        assertTrue(result.contains("content-02.ts"))
        assertFalse(result.contains("preroll.ts"))
        assertFalse(result.contains("#EXT-X-DISCONTINUITY"))
    }

    @Test
    fun matchesKeywordsWithoutCaseSensitivityIncludingQueryString() {
        val playlist = """
            #EXTM3U
            #EXTINF:4.0,
            segment.ts?path=REDTRAFFIC
        """.trimIndent()

        val result = HlsPlaylistAdFilter.filter(playlist, listOf("redtraffic"))

        assertFalse(result.contains("segment.ts"))
    }

    @Test
    fun preservesCueTagsWhenNoSegmentMatches() {
        val playlist = """
            #EXTM3U
            #EXT-X-CUE-OUT:8
            #EXTINF:4.0,
            normal.ts
            #EXT-X-CUE-IN
        """.trimIndent()

        assertEquals(playlist, HlsPlaylistAdFilter.filter(playlist, listOf("advert")))
    }

    @Test
    fun removesOriginalTimelineMarkersWhenAdSegmentWasFiltered() {
        val playlist = """
            #EXTM3U
            #EXT-X-CUE-OUT:8
            #EXTINF:4.0,
            /ad/one.ts
            #EXTINF:4.0,
            /ad/two.ts
            #EXT-X-CUE-IN
            #EXTINF:5.0,
            content.ts
        """.trimIndent()

        val result = HlsPlaylistAdFilter.filter(playlist, listOf("/ad/"))

        assertFalse(result.contains("#EXT-X-CUE-OUT"))
        assertFalse(result.contains("#EXT-X-CUE-IN"))
        assertTrue(result.contains("content.ts"))
    }

    @Test
    fun emptyKeywordListLeavesPlaylistUntouched() {
        val playlist = "#EXTM3U\n#EXTINF:5,\n/ad/segment.ts"
        assertEquals(playlist, HlsPlaylistAdFilter.filter(playlist, emptyList()))
    }

    @Test
    fun leavesMasterPlaylistVariantsUntouched() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            /ads/variant.m3u8
        """.trimIndent()

        assertEquals(playlist, HlsPlaylistAdFilter.filter(playlist, listOf("/ads/")))
    }
}
