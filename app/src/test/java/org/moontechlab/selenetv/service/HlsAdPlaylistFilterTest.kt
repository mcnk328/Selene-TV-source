package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAdPlaylistFilterTest {
    @Test fun removesOnlyKeywordMatchedVODSegments() {
        val source = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:6,
https://cdn.example/video/0001.ts
#EXTINF:6,
https://cdn.example/ads/spot-01.ts
#EXTINF:6,
https://cdn.example/video/0002.ts
#EXT-X-ENDLIST"""
        val result = HlsAdPlaylistFilter.filter(source)
        assertTrue(result.contains("0001.ts"))
        assertFalse(result.contains("spot-01.ts"))
        assertTrue(result.contains("0002.ts"))
    }

    @Test fun removesSegmentsInsideExplicitCueRange() {
        val source = """#EXTM3U
#EXTINF:5,
one.ts
#EXT-X-CUE-OUT:10
#EXTINF:5,
ad-one.ts
#EXTINF:5,
ad-two.ts
#EXT-X-CUE-IN
#EXTINF:5,
two.ts
#EXT-X-ENDLIST"""
        val result = HlsAdPlaylistFilter.filter(source, "")
        assertTrue(result.contains("one.ts"))
        assertFalse(result.contains("ad-one.ts"))
        assertFalse(result.contains("ad-two.ts"))
        assertTrue(result.contains("two.ts"))
        assertFalse(result.contains("#EXT-X-CUE-OUT"))
    }

    @Test fun leavesLiveAndEncryptedPlaylistsUntouched() {
        val live = "#EXTM3U\n#EXTINF:5,\n/ads/a.ts"
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5,\n/ads/a.ts\n#EXT-X-ENDLIST"
        assertEquals(live, HlsAdPlaylistFilter.filter(live))
        assertEquals(encrypted, HlsAdPlaylistFilter.filter(encrypted))
    }

    @Test fun rewritesRelativeSegmentsAndKeyUris() {
        val source = """#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"
#EXTINF:5,
segments/one.ts
#EXT-X-ENDLIST"""
        val result = HlsAdPlaylistFilter.rewriteUris(source, "https://cdn.example/path/index.m3u8") { "LOCAL:$it" }
        assertTrue(result.contains("""URI="LOCAL:https://cdn.example/path/keys/key.bin""""))
        assertTrue(result.contains("LOCAL:https://cdn.example/path/segments/one.ts"))
    }
}
