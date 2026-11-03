package org.moontechlab.selenetv.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/**
 * Rewrites HLS media-playlist responses before ExoPlayer parses them.
 * Segment requests and non-HLS resources pass through unchanged.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsAdFilteringDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        HlsAdFilteringDataSource(upstreamFactory.createDataSource())
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class HlsAdFilteringDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var filteredBody: ByteArray? = null
    private var readPosition = 0
    private var passthroughAfterBuffer = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        filteredBody = null
        readPosition = 0
        passthroughAfterBuffer = false
        val length = upstream.open(dataSpec)
        val path = dataSpec.uri.lastPathSegment.orEmpty()
        if (!path.endsWith(".m3u8", ignoreCase = true) ||
            !AppDataService.isHlsAdSkipEnabledSync()
        ) return length

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = upstream.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count <= 0) continue
            output.write(buffer, 0, count)
            // Avoid buffering unexpectedly large responses. Keep the original
            // playlist intact if it exceeds the conservative manifest limit.
            if (output.size() > MAX_PLAYLIST_BYTES) {
                filteredBody = output.toByteArray()
                passthroughAfterBuffer = true
                return length
            }
        }

        val original = output.toByteArray()
        val rewritten = HlsPlaylistAdFilter.filter(
            original.toString(Charsets.UTF_8),
            AppDataService.getHlsAdSkipKeywordsSync(),
        ).toByteArray(Charsets.UTF_8)
        filteredBody = rewritten
        return rewritten.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val body = filteredBody ?: return upstream.read(buffer, offset, length)
        if (readPosition >= body.size) {
            return if (passthroughAfterBuffer) upstream.read(buffer, offset, length) else C.RESULT_END_OF_INPUT
        }
        val count = minOf(length, body.size - readPosition)
        System.arraycopy(body, readPosition, buffer, offset, count)
        readPosition += count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        filteredBody = null
        readPosition = 0
        passthroughAfterBuffer = false
        upstream.close()
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    }
}
