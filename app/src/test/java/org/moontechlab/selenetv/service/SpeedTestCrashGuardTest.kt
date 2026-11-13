package org.moontechlab.selenetv.service

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SearchResult

/**
 * 测速闪退防线单测。
 *
 * 这组用例锁住三件曾让播放页闪退的事：
 * 1. 不可交给 OkHttp 的集数链接（相对路径 / 无协议头 / 特殊协议）不得进入测速——
 *    否则 `Request.Builder.url()` 抛 IllegalArgumentException，冒到 UI 协程即闪退。
 * 2. 文本 / 二进制的读取必须限长——否则服务端把链接重定向到正片会整段读进内存（播放页 OOM）。
 * 3. 截断的 m3u8 不能把残缺行当真（由 [SpeedTestService.isHttpUrl] 兜住垃圾分片）。
 */
class SpeedTestCrashGuardTest {

    private fun source(vararg episodes: String) = SearchResult(
        id = "1",
        title = "t",
        source = "s",
        sourceName = "S",
        episodes = episodes.toList(),
    )

    @Test
    fun testUrl_prefersSecondEpisode() {
        assertEquals(
            "https://a.com/2.m3u8",
            SpeedTestService.testUrl(source("https://a.com/1.m3u8", "https://a.com/2.m3u8")),
        )
    }

    @Test
    fun testUrl_fallsBackToSingleEpisode() {
        assertEquals("https://a.com/1.m3u8", SpeedTestService.testUrl(source("https://a.com/1.m3u8")))
    }

    @Test
    fun testUrl_returnsNullWithoutEpisodes() {
        assertNull(SpeedTestService.testUrl(source()))
    }

    @Test
    fun testUrl_skipsRelativeUrlInFavorOfAbsolute() {
        // 第 2 集是相对路径：不能选它，回落到可测的第 1 集
        assertEquals(
            "https://a.com/1.m3u8",
            SpeedTestService.testUrl(source("https://a.com/1.m3u8", "/play/2.m3u8")),
        )
    }

    @Test
    fun testUrl_returnsNullWhenOnlyUnsupportedUrls() {
        assertNull(SpeedTestService.testUrl(source("/play/1.m3u8", "magnet:?xt=urn:btih:xx")))
    }

    @Test
    fun isHttpUrl_acceptsOnlyAbsoluteHttpSchemes() {
        assertTrue(SpeedTestService.isHttpUrl("http://a.com/1.m3u8"))
        assertTrue(SpeedTestService.isHttpUrl("HTTPS://a.com/1.m3u8"))
        assertTrue(SpeedTestService.isHttpUrl("  https://a.com/1.m3u8  "))
        assertFalse(SpeedTestService.isHttpUrl(null))
        assertFalse(SpeedTestService.isHttpUrl(""))
        assertFalse(SpeedTestService.isHttpUrl("   "))
        assertFalse(SpeedTestService.isHttpUrl("/play/1.m3u8"))
        assertFalse(SpeedTestService.isHttpUrl("rtmp://a.com/live"))
        assertFalse(SpeedTestService.isHttpUrl("magnet:?xt=urn:btih:xx"))
    }

    @Test
    fun readCapped_truncatesAtCap() {
        val body = ByteArray(200_000) { 'a'.code.toByte() }.toResponseBody()
        val (bytes, truncated) = SpeedTestService.readCapped(body, 1024)!!
        assertEquals(1024, bytes.size)
        assertTrue(truncated)
    }

    @Test
    fun readCapped_keepsWholeBodyWhenUnderCap() {
        val body = "short".toByteArray().toResponseBody()
        val (bytes, truncated) = SpeedTestService.readCapped(body, 1024)!!
        assertEquals("short", String(bytes))
        assertFalse(truncated)
    }

    @Test
    fun readCapped_returnsNullForMissingBody() {
        assertNull(SpeedTestService.readCapped(null, 1024))
    }
}
