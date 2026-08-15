// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/** Desired dynamic-range result for the bounded desktop HLS adapter. */
public enum class FfmpegHlsVideoOutputPolicy {
    /** Preserve compressed video samples and their verified color signal. */
    PRESERVE_SOURCE,

    /** Keep confirmed SDR unchanged; tone-map only explicit HDR10, HDR10+, or HLG input to SDR. */
    FORCE_SDR,

    /** Decode to limited-range BT.709 AVC and AAC so Apple AVFoundation can consume legacy input. */
    AVFOUNDATION_COMPATIBLE_SDR,
}

/** Segment container exposed by the bounded local HLS origin. */
public enum class FfmpegHlsSegmentContainer {
    CMAF_FMP4,
    MPEG2_TS,
}

public data class FfmpegHlsPlaybackRequest(
    public val input: MediaInput,
    public val selectedVideoTrackId: Int? = null,
    public val selectedAudioTrackId: Int? = null,
    public val selectedSubtitleTrackId: Int? = null,
    public val videoOutputPolicy: FfmpegHlsVideoOutputPolicy = FfmpegHlsVideoOutputPolicy.PRESERVE_SOURCE,
    public val segmentContainer: FfmpegHlsSegmentContainer = FfmpegHlsSegmentContainer.CMAF_FMP4,
    public val startTimeUs: Long = 0L,
    public val fragmentDurationUs: Long = 4_000_000L,
    // FFmpeg may cut at keyframes before the requested fragment duration. A small fragment-count
    // window can therefore evict the consumer's current VOD position even while byte usage is low.
    public val maxBufferedFragments: Int = 256,
    public val maxBufferedBytes: Long = 96L * 1024L * 1024L,
) {
    init {
        require(startTimeUs >= 0L) { "The playback start time cannot be negative." }
        require(fragmentDurationUs > 0L) { "The fragment duration must be positive." }
        require(maxBufferedFragments >= 3) { "At least three media fragments must be retained." }
        require(maxBufferedBytes > 0L) { "The fragment byte limit must be positive." }
        require(
            segmentContainer != FfmpegHlsSegmentContainer.MPEG2_TS ||
                videoOutputPolicy == FfmpegHlsVideoOutputPolicy.AVFOUNDATION_COMPATIBLE_SDR,
        ) { "MPEG-TS HLS requires the AVC/AAC compatibility output policy." }
    }
}

public data class FfmpegHlsPlaybackSource(
    public val playlistUrl: String,
    public val probe: MediaProbe,
    public val outputInfo: MediaOutputInfo,
    public val playbackOffsetUs: Long,
    public val segmentContainer: FfmpegHlsSegmentContainer = FfmpegHlsSegmentContainer.CMAF_FMP4,
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
    private val origin: FfmpegHlsOrigin,
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
        val videoHandling = request.resolveVideoHandling(probe)
        val bridgeRequest =
            BridgeRequest(
                output = BridgeOutput.CMAF_FRAGMENT_STREAM,
                videoHandling = videoHandling,
                audioHandling = request.resolveAudioHandling(),
                subtitleHandling =
                    if (request.selectedSubtitleTrackId == null) {
                        SubtitleHandling.OMIT
                    } else {
                        SubtitleHandling.BURN_IN
                    },
                fragmentDurationUs = request.fragmentDurationUs,
                preferredVideoTrackId = request.selectedVideoTrackId,
                preferredAudioTrackId = request.selectedAudioTrackId,
                preferredSubtitleTrackId = request.selectedSubtitleTrackId,
            )
        val origin: FfmpegHlsOrigin =
            when (request.segmentContainer) {
                FfmpegHlsSegmentContainer.CMAF_FMP4 -> {
                    val bridgeSession = driver.open(request.input, bridgeRequest)
                    if (request.startTimeUs > 0L) bridgeSession.seekTo(request.startTimeUs)
                    BoundedCmafHlsOrigin(
                        session = bridgeSession,
                        maxBufferedFragments = request.maxBufferedFragments,
                        maxBufferedBytes = request.maxBufferedBytes,
                    )
                }
                FfmpegHlsSegmentContainer.MPEG2_TS -> {
                    val prepared = driver.prepare(request.input, bridgeRequest)
                    BoundedMpegTsHlsOrigin(
                        driver = driver,
                        input = request.input,
                        outputInfo = prepared.outputInfo,
                        startTimeUs = request.startTimeUs,
                        segmentDurationUs = request.fragmentDurationUs,
                        maxBufferedFragments = request.maxBufferedFragments,
                        maxBufferedBytes = request.maxBufferedBytes,
                    )
                }
            }
        try {
            val outputInfo = origin.startAndAwaitReady()
            return BundledFfmpegHlsPlaybackSession(
                source =
                    FfmpegHlsPlaybackSource(
                        playlistUrl = origin.playlistUrl,
                        probe = probe,
                        outputInfo = outputInfo,
                        playbackOffsetUs = request.startTimeUs,
                        segmentContainer = request.segmentContainer,
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

internal interface FfmpegHlsOrigin {
    val playlistUrl: String

    suspend fun startAndAwaitReady(): MediaOutputInfo

    suspend fun closeAsync()
}

internal fun FfmpegHlsPlaybackRequest.resolveVideoHandling(probe: MediaProbe): VideoHandling {
    if (selectedSubtitleTrackId != null) return VideoHandling.TRANSCODE_TO_SDR
    if (videoOutputPolicy == FfmpegHlsVideoOutputPolicy.AVFOUNDATION_COMPATIBLE_SDR) {
        return VideoHandling.TRANSCODE_TO_SDR
    }
    if (videoOutputPolicy == FfmpegHlsVideoOutputPolicy.PRESERVE_SOURCE) return VideoHandling.COPY

    val video =
        selectedVideoTrackId
            ?.let { selected -> probe.tracks.filterIsInstance<VideoTrackInfo>().firstOrNull { it.id == selected } }
            ?: probe.tracks.filterIsInstance<VideoTrackInfo>().firstOrNull()
            ?: return VideoHandling.TONE_MAP_TO_SDR
    return if (
        video.colorInfo.dynamicRange == DynamicRangeFormat.SDR &&
        video.colorInfo.dolbyVision == null
    ) {
        VideoHandling.COPY
    } else {
        VideoHandling.TONE_MAP_TO_SDR
    }
}

internal fun FfmpegHlsPlaybackRequest.resolveAudioHandling(): AudioHandling =
    if (videoOutputPolicy == FfmpegHlsVideoOutputPolicy.AVFOUNDATION_COMPATIBLE_SDR) {
        AudioHandling.TRANSCODE_AAC
    } else {
        AudioHandling.COPY
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

internal class BoundedMpegTsHlsOrigin(
    private val driver: BundledFfmpegNativeDriver,
    private val input: MediaInput,
    private val outputInfo: MediaOutputInfo,
    private val startTimeUs: Long,
    private val segmentDurationUs: Long,
    maxBufferedFragments: Int,
    maxBufferedBytes: Long,
) : FfmpegHlsOrigin {
    private val closed = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>(null)
    private val firstPresentationTimeUs = AtomicLong(UNSET_TIME)
    private val streamStartedNanos = AtomicLong(UNSET_TIME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = Files.createTempDirectory("kmediabridge-cast-hls-")
    private val playlistPath = directory.resolve("stream.m3u8")
    private val segmentPathPattern = directory.resolve("segment-%05d.ts")
    private val maximumPlaylistSegments =
        maximumPlaylistSegments(
            fragmentDurationUs = segmentDurationUs,
            maxBufferedFragments = maxBufferedFragments,
            maxBufferedBytes = maxBufferedBytes,
        )
    private var worker: Job? = null
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 0).apply {
            createContext("/", ::serve)
            start()
        }

    override val playlistUrl: String = "http://$LOOPBACK_HOST:${server.address.port}$MASTER_PLAYLIST_PATH"

    override suspend fun startAndAwaitReady(): MediaOutputInfo {
        check(worker == null) { "The MPEG-TS HLS origin has already been started." }
        worker =
            scope.launch {
                try {
                    driver.transcodeCastMpegTsHls(
                        input = input,
                        outputInfo = outputInfo,
                        playlistPath = playlistPath,
                        segmentPathPattern = segmentPathPattern,
                        segmentDurationUs = segmentDurationUs,
                        maximumPlaylistSegments = maximumPlaylistSegments,
                        startTimeUs = startTimeUs,
                        continueAt = ::continueAt,
                    )
                } catch (error: Throwable) {
                    if (!closed.get()) failure.compareAndSet(null, error)
                } finally {
                    completed.set(true)
                }
            }
        return try {
            withTimeout(READY_TIMEOUT) {
                while (true) {
                    failure.get()?.let { throw it }
                    if (playlistIsReady()) return@withTimeout outputInfo
                    if (completed.get()) {
                        throw MediaBridgeException(
                            MediaBridgeErrorCode.CONVERSION_FAILED,
                            "The MPEG-TS conversion ended before producing a playable HLS segment.",
                        )
                    }
                    delay(READY_POLL_MILLIS)
                }
                @Suppress("UNREACHABLE_CODE")
                outputInfo
            }
        } catch (error: Throwable) {
            throw MediaBridgeException(
                MediaBridgeErrorCode.CONVERSION_FAILED,
                "The selected FFmpeg bridge did not produce a playable MPEG-TS HLS stream.",
                error,
            )
        }
    }

    override suspend fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        worker?.cancelAndJoin()
        worker = null
        scope.cancel()
        runCatching {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }

    private fun continueAt(presentationTimeUs: Long): Boolean {
        if (closed.get()) return false
        val first = firstPresentationTimeUs.updateFirst(presentationTimeUs)
        val started = streamStartedNanos.updateFirst(System.nanoTime())
        val mediaElapsedUs = (presentationTimeUs - first).coerceAtLeast(0L)
        while (!closed.get()) {
            val elapsedUs = ((System.nanoTime() - started) / NANOS_PER_MICROSECOND).coerceAtLeast(0L)
            val delayUs = mediaElapsedUs - elapsedUs - PREBUFFER_US
            if (delayUs <= 0L) return true
            Thread.sleep(min(delayUs / MICROSECONDS_PER_MILLISECOND + 1L, MAXIMUM_PACING_SLEEP_MILLIS))
        }
        return false
    }

    private fun playlistIsReady(): Boolean {
        if (!Files.isRegularFile(playlistPath)) return false
        val playlist = runCatching { Files.readString(playlistPath, StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val segmentReferences =
            playlist
                .lineSequence()
                .map(String::trim)
                .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                .toList()
        val minimumSegments = if ("#EXT-X-ENDLIST" in playlist) 1 else MINIMUM_LIVE_READY_SEGMENTS
        if (segmentReferences.size < minimumSegments) return false
        return segmentReferences.all { segmentReference ->
            val segment = directory.resolve(Path.of(segmentReference).fileName.toString()).normalize()
            segment.startsWith(directory) && Files.isRegularFile(segment) && Files.size(segment) > 0L
        }
    }

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod !in SUPPORTED_HTTP_METHODS) {
                exchange.sendResponseHeaders(405, -1L)
                return
            }
            if (exchange.requestURI.path == MASTER_PLAYLIST_PATH) {
                val bytes = masterPlaylist().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", HLS_CONTENT_TYPE)
                exchange.responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate")
                sendBody(exchange, bytes, 0, bytes.lastIndex, 200)
                return
            }
            val file =
                when {
                    exchange.requestURI.path == "/stream.m3u8" -> playlistPath
                    MPEG_TS_SEGMENT_PATH.matches(exchange.requestURI.path) ->
                        directory.resolve(exchange.requestURI.path.removePrefix("/")).normalize()
                    else -> null
                }
            if (file == null || !file.startsWith(directory) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1L)
                return
            }
            val bytes = Files.readAllBytes(file)
            val isPlaylist = file == playlistPath
            exchange.responseHeaders.add(
                "Content-Type",
                if (isPlaylist) HLS_CONTENT_TYPE else MPEG_TS_CONTENT_TYPE,
            )
            exchange.responseHeaders.add("Cache-Control", "no-cache, no-store, must-revalidate")
            if (isPlaylist) {
                sendBody(exchange, bytes, 0, bytes.lastIndex, 200)
            } else {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                val range = parseByteRange(exchange.requestHeaders.getFirst("Range"), bytes.size)
                if (range == INVALID_RANGE) {
                    exchange.responseHeaders.add("Content-Range", "bytes */${bytes.size}")
                    exchange.sendResponseHeaders(416, -1L)
                    return
                }
                val start = range?.first ?: 0
                val end = range?.last ?: bytes.lastIndex
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                sendBody(exchange, bytes, start, end, if (range == null) 200 else 206)
            }
        } catch (_: Throwable) {
            runCatching { exchange.sendResponseHeaders(500, -1L) }
        } finally {
            exchange.close()
        }
    }

    private fun masterPlaylist(): String {
        val codecs =
            buildString {
                append(COMPATIBILITY_AVC_CODEC)
                if (outputInfo.audioHandling == AudioHandling.TRANSCODE_AAC) append(",").append(AAC_LC_CODEC)
            }
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:6")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=$ESTIMATED_OUTPUT_BITRATE,CODECS=\"$codecs\"")
            appendLine("stream.m3u8")
        }
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

    private companion object {
        val READY_TIMEOUT = 30.seconds
        const val LOOPBACK_HOST = "127.0.0.1"
        const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
        const val MPEG_TS_CONTENT_TYPE = "video/mp2t"
        const val MASTER_PLAYLIST_PATH = "/master.m3u8"
        const val COMPATIBILITY_AVC_CODEC = "avc1.640028"
        const val AAC_LC_CODEC = "mp4a.40.2"
        const val MINIMUM_LIVE_READY_SEGMENTS = 3
        const val PREBUFFER_US = 12_000_000L
        const val NANOS_PER_MICROSECOND = 1_000L
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
        const val MAXIMUM_PACING_SLEEP_MILLIS = 100L
        const val READY_POLL_MILLIS = 50L
        const val ESTIMATED_OUTPUT_BITRATE = 18_000_000L
        const val BITS_PER_BYTE = 8L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val UNSET_TIME = Long.MIN_VALUE
        val MPEG_TS_SEGMENT_PATH = Regex("/segment-\\d+\\.ts")
        val SUPPORTED_HTTP_METHODS = setOf("GET", "HEAD")

        fun maximumPlaylistSegments(
            fragmentDurationUs: Long,
            maxBufferedFragments: Int,
            maxBufferedBytes: Long,
        ): Int {
            val estimatedSegmentBytes =
                (fragmentDurationUs.toDouble() * ESTIMATED_OUTPUT_BITRATE / BITS_PER_BYTE / MICROSECONDS_PER_SECOND)
                    .toLong()
                    .coerceAtLeast(1L)
            val byteBounded = (maxBufferedBytes / estimatedSegmentBytes).coerceAtLeast(3L).coerceAtMost(Int.MAX_VALUE.toLong())
            return min(maxBufferedFragments, byteBounded.toInt()).coerceAtLeast(3)
        }

        fun AtomicLong.updateFirst(value: Long): Long {
            compareAndSet(UNSET_TIME, value)
            return get()
        }

        fun parseByteRange(
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

        val BYTE_RANGE = Regex("bytes=(\\d+)-(\\d*)", RegexOption.IGNORE_CASE)
        val INVALID_RANGE: IntRange = IntRange.EMPTY
    }
}

internal class BoundedCmafHlsOrigin(
    private val session: MediaBridgeSession,
    private val maxBufferedFragments: Int,
    private val maxBufferedBytes: Long,
) : FfmpegHlsOrigin {
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

    override val playlistUrl: String = "http://127.0.0.1:${server.address.port}/stream.m3u8"

    override suspend fun startAndAwaitReady(): MediaOutputInfo {
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
                "The selected FFmpeg bridge did not produce a playable CMAF stream.",
                error,
            )
        }
    }

    override suspend fun closeAsync() {
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
                            "The selected FFmpeg bridge reached end of stream before producing playable CMAF.",
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
                "The selected FFmpeg bridge stopped before producing media fragments.",
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
