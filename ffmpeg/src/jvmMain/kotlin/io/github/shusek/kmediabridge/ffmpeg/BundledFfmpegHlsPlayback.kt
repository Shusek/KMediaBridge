// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.BridgeOutput
import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaFragment
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaOutputInfo
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoCodec
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

public data class FfmpegHlsPlaybackRequest(
    public val input: MediaInput,
    public val selectedVideoTrackId: Int? = null,
    public val selectedAudioTrackId: Int? = null,
    public val startTimeUs: Long = 0L,
    public val fragmentDurationUs: Long = 4_000_000L,
    public val maxBufferedFragments: Int = 12,
    public val maxBufferedBytes: Long = 96L * 1024L * 1024L,
) {
    init {
        require(startTimeUs >= 0L) { "The playback start time cannot be negative." }
        require(fragmentDurationUs > 0L) { "The fragment duration must be positive." }
        require(maxBufferedFragments >= 3) { "At least three media fragments must be retained." }
        require(maxBufferedBytes > 0L) { "The fragment byte limit must be positive." }
    }
}

public data class FfmpegHlsPlaybackSource(
    public val playlistUrl: String,
    public val probe: MediaProbe,
    public val outputInfo: MediaOutputInfo,
    public val playbackOffsetUs: Long,
    /** Compressed HDR picture samples copied into CMAF; this is not a claim about display output. */
    public val copiedHdrSignal: FfmpegCmafHdrSampleCopy,
)

public enum class FfmpegCmafHdrSampleCopy {
    NONE,
    HDR10,
    HDR10_PLUS,
    HLG,
}

public class BundledFfmpegHlsPlaybackSession internal constructor(
    public val source: FfmpegHlsPlaybackSource,
    private val origin: BoundedCmafHlsOrigin,
) : Closeable {
    public suspend fun closeAsync(): Unit = origin.closeAsync()

    override fun close(): Unit = runBlocking { closeAsync() }
}

public object BundledFfmpegHlsPlaybackBackend {
    @JvmStatic
    @JvmOverloads
    public suspend fun start(
        request: FfmpegHlsPlaybackRequest,
        driver: BundledFfmpegNativeDriver = BundledFfmpegNativeDriver.loadDefault(),
    ): BundledFfmpegHlsPlaybackSession {
        val probe = driver.probe(request.input)
        val bridgeSession =
            driver.open(
                request.input,
                BridgeRequest(
                    output = BridgeOutput.CMAF_FRAGMENT_STREAM,
                    videoHandling = VideoHandling.COPY,
                    audioHandling = AudioHandling.COPY,
                    subtitleHandling = SubtitleHandling.OMIT,
                    fragmentDurationUs = request.fragmentDurationUs,
                    preferredVideoTrackId = request.selectedVideoTrackId,
                    preferredAudioTrackId = request.selectedAudioTrackId,
                ),
            )
        if (request.startTimeUs > 0L) bridgeSession.seekTo(request.startTimeUs)
        val origin =
            BoundedCmafHlsOrigin(
                session = bridgeSession,
                maxBufferedFragments = request.maxBufferedFragments,
                maxBufferedBytes = request.maxBufferedBytes,
            )
        try {
            val outputInfo = origin.startAndAwaitReady()
            return BundledFfmpegHlsPlaybackSession(
                source =
                    FfmpegHlsPlaybackSource(
                        playlistUrl = origin.playlistUrl,
                        probe = probe,
                        outputInfo = outputInfo,
                        playbackOffsetUs = request.startTimeUs,
                        copiedHdrSignal = probe.copiedHdrSignal(outputInfo),
                    ),
                origin = origin,
            )
        } catch (error: Throwable) {
            origin.closeAsync()
            throw error
        }
    }
}

internal fun MediaProbe.copiedHdrSignal(outputInfo: MediaOutputInfo): FfmpegCmafHdrSampleCopy {
    if (outputInfo.videoHandling != VideoHandling.COPY) return FfmpegCmafHdrSampleCopy.NONE
    val video =
        tracks
            .filterIsInstance<VideoTrackInfo>()
            .firstOrNull { it.id == outputInfo.selectedVideoTrackId }
            ?: return FfmpegCmafHdrSampleCopy.NONE
    val color = outputInfo.outputColorInfo ?: return FfmpegCmafHdrSampleCopy.NONE
    val isUnmodifiedRec2020Hevc =
        video.codec == VideoCodec.HEVC &&
            color.bitDepth == 10 &&
            color.primaries == ColorPrimaries.BT2020 &&
            color.matrix in setOf(ColorMatrix.BT2020_NCL, ColorMatrix.BT2020_CL)
    if (!isUnmodifiedRec2020Hevc || color.dolbyVision != null) return FfmpegCmafHdrSampleCopy.NONE

    return when {
        color.dynamicRange == DynamicRangeFormat.HDR10_PLUS && color.transfer == ColorTransfer.PQ ->
            FfmpegCmafHdrSampleCopy.HDR10_PLUS
        color.dynamicRange == DynamicRangeFormat.HDR10 && color.transfer == ColorTransfer.PQ ->
            FfmpegCmafHdrSampleCopy.HDR10
        color.dynamicRange == DynamicRangeFormat.HLG && color.transfer == ColorTransfer.HLG ->
            FfmpegCmafHdrSampleCopy.HLG
        else -> FfmpegCmafHdrSampleCopy.NONE
    }
}

internal class BoundedCmafHlsOrigin(
    private val session: MediaBridgeSession,
    private val maxBufferedFragments: Int,
    private val maxBufferedBytes: Long,
) {
    private val lock = Any()
    private val ready = CompletableDeferred<MediaOutputInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fragments = LinkedHashMap<Long, MediaFragment>()
    private var initialization: ByteArray? = null
    private var outputInfo: MediaOutputInfo? = null
    private var bufferedBytes = 0L
    private var endOfStream = false
    private var failure: Throwable? = null
    private var collector: Job? = null
    private val closed = AtomicBoolean(false)
    private var firstPresentationTimeUs: Long? = null
    private var streamStartedNanos: Long? = null
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 0).apply {
            createContext("/", ::serve)
            start()
        }

    val playlistUrl: String = "http://127.0.0.1:${server.address.port}/stream.m3u8"

    suspend fun startAndAwaitReady(): MediaOutputInfo {
        check(collector == null) { "The CMAF origin has already been started." }
        collector =
            scope.launch {
                try {
                    session.events.collect(::accept)
                } catch (error: Throwable) {
                    synchronized(lock) { failure = error }
                    ready.completeExceptionally(error)
                }
            }
        return try {
            withTimeout(READY_TIMEOUT) { ready.await() }
        } catch (error: Throwable) {
            throw MediaBridgeException(
                MediaBridgeErrorCode.CONVERSION_FAILED,
                "The bundled FFmpeg bridge did not produce a playable CMAF stream.",
                error,
            )
        }
    }

    suspend fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        collector?.cancel()
        collector = null
        try {
            session.close()
        } finally {
            server.stop(0)
            scope.cancel()
            synchronized(lock) {
                fragments.clear()
                initialization = null
                bufferedBytes = 0L
            }
        }
    }

    private suspend fun accept(event: MediaBridgeEvent) {
        when (event) {
            is MediaBridgeEvent.OutputConfigured -> {
                synchronized(lock) { outputInfo = event.value }
                completeWhenReady()
            }
            is MediaBridgeEvent.Fragment -> acceptFragment(event.value)
            is MediaBridgeEvent.Discontinuity -> Unit
            MediaBridgeEvent.EndOfStream -> {
                synchronized(lock) { endOfStream = true }
                completeWhenReady()
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        MediaBridgeException(
                            MediaBridgeErrorCode.CONVERSION_FAILED,
                            "The bundled FFmpeg bridge reached end of stream before producing playable CMAF.",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun acceptFragment(fragment: MediaFragment) {
        if (!fragment.isInitialization) throttle(fragment)
        synchronized(lock) {
            if (fragment.bytes.size.toLong() > maxBufferedBytes) {
                throw MediaBridgeException(
                    MediaBridgeErrorCode.CONVERSION_FAILED,
                    "A generated CMAF fragment exceeds the configured bounded-memory limit.",
                )
            }
            if (fragment.isInitialization) {
                initialization = fragment.bytes.copyOf()
            } else {
                fragments.remove(fragment.sequence)?.let { previous -> bufferedBytes -= previous.bytes.size.toLong() }
                fragments[fragment.sequence] = fragment.copy(bytes = fragment.bytes.copyOf())
                bufferedBytes += fragment.bytes.size.toLong()
                evictOldestFragments()
            }
        }
        completeWhenReady()
    }

    private suspend fun throttle(fragment: MediaFragment) {
        val firstPresentation = firstPresentationTimeUs ?: fragment.presentationTimeUs.also { firstPresentationTimeUs = it }
        val started = streamStartedNanos ?: System.nanoTime().also { streamStartedNanos = it }
        val mediaEndUs = (fragment.presentationTimeUs - firstPresentation + fragment.durationUs).coerceAtLeast(0L)
        val elapsedUs = ((System.nanoTime() - started) / NANOS_PER_MICROSECOND).coerceAtLeast(0L)
        val delayUs = mediaEndUs - elapsedUs - PREBUFFER_US
        if (delayUs > 0L) delay(delayUs / MICROSECONDS_PER_MILLISECOND)
    }

    private fun evictOldestFragments() {
        while (fragments.size > maxBufferedFragments || bufferedBytes > maxBufferedBytes) {
            val oldest = fragments.entries.firstOrNull() ?: break
            fragments.remove(oldest.key)
            bufferedBytes -=
                oldest.value.bytes.size
                    .toLong()
        }
    }

    private fun completeWhenReady() {
        val configured =
            synchronized(lock) {
                outputInfo?.takeIf { initialization != null && fragments.isNotEmpty() }
            }
        if (configured != null) ready.complete(configured)
    }

    private fun serve(exchange: HttpExchange) {
        try {
            when (exchange.requestURI.path) {
                "/", "/stream.m3u8" -> servePlaylist(exchange)
                "/init.mp4" -> serveBytes(exchange, "video/mp4", synchronized(lock) { initialization })
                else -> {
                    val sequence =
                        SEGMENT_PATH
                            .matchEntire(exchange.requestURI.path)
                            ?.groupValues
                            ?.get(1)
                            ?.toLongOrNull()
                    val bytes = sequence?.let { synchronized(lock) { fragments[it]?.bytes } }
                    serveBytes(exchange, "video/mp4", bytes)
                }
            }
        } catch (_: Throwable) {
            runCatching { exchange.sendResponseHeaders(500, -1L) }
        } finally {
            exchange.close()
        }
    }

    private fun servePlaylist(exchange: HttpExchange) {
        if (exchange.requestMethod !in SUPPORTED_HTTP_METHODS) {
            exchange.sendResponseHeaders(405, -1L)
            return
        }
        val body = playlist().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
        exchange.responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate")
        sendBody(exchange, body, 0, body.size - 1, 200)
    }

    private fun playlist(): String {
        val snapshot = synchronized(lock) { fragments.values.toList() }
        val ended = synchronized(lock) { endOfStream }
        val failed = synchronized(lock) { failure }
        if (snapshot.isEmpty() && failed != null) {
            throw MediaBridgeException(
                MediaBridgeErrorCode.CONVERSION_FAILED,
                "The bundled FFmpeg bridge stopped before producing media fragments.",
                failed,
            )
        }
        val targetDuration =
            snapshot
                .maxOfOrNull { ceil(it.durationUs.toDouble() / MICROSECONDS_PER_SECOND).toInt() }
                ?.coerceAtLeast(1)
                ?: 1
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:${snapshot.firstOrNull()?.sequence ?: 1L}")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
            snapshot.forEach { fragment ->
                appendLine(String.format(Locale.ROOT, "#EXTINF:%.6f,", fragment.durationUs / MICROSECONDS_PER_SECOND))
                appendLine("segment-${fragment.sequence}.m4s")
            }
            if (ended) appendLine("#EXT-X-ENDLIST")
        }
    }

    private fun serveBytes(
        exchange: HttpExchange,
        contentType: String,
        bytes: ByteArray?,
    ) {
        if (exchange.requestMethod !in SUPPORTED_HTTP_METHODS) {
            exchange.sendResponseHeaders(405, -1L)
            return
        }
        if (bytes == null) {
            exchange.sendResponseHeaders(404, -1L)
            return
        }
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        val range = parseRange(exchange.requestHeaders.getFirst("Range"), bytes.size)
        if (range == INVALID_RANGE) {
            exchange.responseHeaders.add("Content-Range", "bytes */${bytes.size}")
            exchange.sendResponseHeaders(416, -1L)
            return
        }
        val start = range?.first ?: 0
        val end = range?.last ?: bytes.lastIndex
        val status = if (range == null) 200 else 206
        if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
        sendBody(exchange, bytes, start, end, status)
    }

    private fun sendBody(
        exchange: HttpExchange,
        bytes: ByteArray,
        start: Int,
        end: Int,
        status: Int,
    ) {
        val length = (end - start + 1).coerceAtLeast(0)
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", length.toString())
            exchange.sendResponseHeaders(status, -1L)
            return
        }
        exchange.sendResponseHeaders(status, length.toLong())
        exchange.responseBody.use { output -> output.write(bytes, start, length) }
    }

    private fun parseRange(
        value: String?,
        size: Int,
    ): IntRange? {
        if (value.isNullOrBlank()) return null
        val match = BYTE_RANGE.matchEntire(value.trim()) ?: return INVALID_RANGE
        val start = match.groupValues[1].toIntOrNull() ?: return INVALID_RANGE
        val requestedEnd = match.groupValues[2].toIntOrNull() ?: (size - 1)
        if (start < 0 || start >= size || requestedEnd < start) return INVALID_RANGE
        return start..requestedEnd.coerceAtMost(size - 1)
    }

    private companion object {
        val READY_TIMEOUT = 30.seconds
        const val LOOPBACK_HOST: String = "127.0.0.1"
        const val PREBUFFER_US: Long = 12_000_000L
        const val NANOS_PER_MICROSECOND: Long = 1_000L
        const val MICROSECONDS_PER_MILLISECOND: Long = 1_000L
        const val MICROSECONDS_PER_SECOND: Double = 1_000_000.0
        val SEGMENT_PATH = Regex("/segment-(\\d+)\\.m4s")
        val BYTE_RANGE = Regex("bytes=(\\d+)-(\\d*)", RegexOption.IGNORE_CASE)
        val INVALID_RANGE: IntRange = IntRange.EMPTY
        val SUPPORTED_HTTP_METHODS = setOf("GET", "HEAD")
    }
}
