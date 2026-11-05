package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAdRuleEngineTest {
    @Test fun recognizesKnownProviderFamiliesWithoutTreatingProviderAsAdEvidence() {
        assertEquals(HlsAdRuleEngine.Provider.QUANTUM, HlsAdRuleEngine.detectProvider("https://cdnlz.example/video/index.m3u8"))
        assertEquals(HlsAdRuleEngine.Provider.FEIFAN, HlsAdRuleEngine.detectProvider("https://ffzy.example/video.m3u8"))
        assertEquals(HlsAdRuleEngine.Provider.BAOFENG, HlsAdRuleEngine.detectProvider("https://bfeng.example/video.m3u8"))
        assertEquals(HlsAdRuleEngine.Provider.KUAIKAN, HlsAdRuleEngine.detectProvider("https://kuaikan.example/video.m3u8"))
        assertFalse(HlsAdRuleEngine.isAdUri("https://ffzy.example/123456789012.ts", HlsPlaylistAdFilter.defaultKeywords))
    }

    @Test fun parsesQuantumSequenceNumbersAndDiscontinuityWithoutDeletingAnything() {
        val playlist = "#EXTM3U\n#EXTINF:6.0,\nabcdefghijk0000001.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:6.0,\nabcdefghijk0000003.ts"
        val segments = HlsAdRuleEngine.parseSegments(playlist, "https://cdnlz.example/index.m3u8")
        assertEquals(listOf(1L, 3L), segments.mapNotNull { it.sequenceNumber })
        assertTrue(segments[1].hasDiscontinuityBefore)
        assertEquals(1, HlsAdRuleEngine.findSequenceAnomalies(segments).size)
    }

    @Test fun sequenceAnomalyIsDiagnosticAndNeverAnAutomaticAdMatch() {
        val segments = HlsAdRuleEngine.parseSegments("#EXTM3U\n#EXTINF:5,\npart-10.ts\n#EXTINF:5,\npart-2.ts\n#EXTINF:5,\npart-11.ts")
        assertTrue(HlsAdRuleEngine.findSequenceAnomalies(segments).isEmpty())
        assertFalse(HlsAdRuleEngine.isAdUri(segments[1].uri, listOf("/ads/")))
    }

    @Test fun keywordMatchingIsCaseInsensitiveAndIncludesQueryString() {
        assertTrue(HlsAdRuleEngine.isAdUri("segment.ts?tag=REDTRAFFIC", listOf("redtraffic")))
    }
}
