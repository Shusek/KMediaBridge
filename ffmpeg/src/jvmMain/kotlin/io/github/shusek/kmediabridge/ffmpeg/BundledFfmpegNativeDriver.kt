// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.AudioTrackInfo
import io.github.shusek.kmediabridge.BridgeCapabilities
import io.github.shusek.kmediabridge.BridgeOutput
import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.BridgeSupport
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DolbyVisionHandling
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.MediaOutputInfo
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.SubtitleTrackInfo
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A desktop JVM driver backed by either the optional bundled FFmpeg runtime
 * artifact or an explicitly selected compatible external runtime.
 *
 * No FFmpeg executable is used. An external directory must contain its own
 * KMediaBridge native bridge and `manifest.properties`. External runtime
 * licensing is caller-managed and may differ from the bundled LGPL payload.
 */
public class BundledFfmpegNativeDriver private constructor(
    private val runtime: LoadedFfmpegRuntime,
) : FfmpegNativeDriver {
    override val runtimeInfo: FfmpegRuntimeInfo = runtime.runtimeInfo

    override val capabilities: BridgeCapabilities = runtime.capabilities

    override suspend fun evaluate(
        input: MediaInput,
        request: BridgeRequest,
    ): BridgeSupport {
        val reason = unsupportedReason(input, request)
        return if (reason == null) {
            BridgeSupport.Supported(
                confidence = if (request.subtitleHandling == SubtitleHandling.BURN_IN) 80 else 90,
                reason =
                    if (request.subtitleHandling == SubtitleHandling.BURN_IN) {
                        "The selected FFmpeg runtime can decode SDR video and composite text subtitles through libass."
                    } else {
                        "The selected FFmpeg runtime can remux this local input without re-encoding video."
                    },
            )
        } else {
            BridgeSupport.Unsupported(reason)
        }
    }

    override suspend fun probe(input: MediaInput): MediaProbe {
        requireLocalUnencryptedInput(input)
        val document = withContext(Dispatchers.IO) { runtime.probeJson(input.locator) }
        return NativeProbeParser.parse(document)
    }

    override suspend fun open(
        input: MediaInput,
        request: BridgeRequest,
    ): MediaBridgeSession {
        unsupportedReason(input, request)?.let { reason ->
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, reason)
        }
        val probe = probe(input)
        val videoTracks = probe.tracks.filterIsInstance<VideoTrackInfo>()
        val audioTracks = probe.tracks.filterIsInstance<AudioTrackInfo>()
        val subtitleTracks = probe.tracks.filterIsInstance<SubtitleTrackInfo>()
        val videoTrack =
            request.preferredVideoTrackId
                ?.let { requested -> videoTracks.firstOrNull { it.id == requested } }
                ?: videoTracks.firstOrNull()
                ?: throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_INPUT, "No video track is available.")
        val audioTrack =
            if (request.audioHandling == AudioHandling.OMIT) {
                null
            } else {
                request.preferredAudioTrackId
                    ?.let { requested -> audioTracks.firstOrNull { it.id == requested } }
                    ?: audioTracks.firstOrNull(AudioTrackInfo::isDefault)
                    ?: audioTracks.firstOrNull()
            }
        val subtitleTrack =
            if (request.subtitleHandling == SubtitleHandling.OMIT) {
                null
            } else {
                request.preferredSubtitleTrackId
                    ?.let { requested -> subtitleTracks.firstOrNull { it.id == requested } }
                    ?: subtitleTracks.firstOrNull(SubtitleTrackInfo::isDefault)
                    ?: subtitleTracks.firstOrNull()
            }
        if (request.preferredVideoTrackId != null && videoTrack.id != request.preferredVideoTrackId) {
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, "The requested video track is unavailable.")
        }
        if (request.preferredAudioTrackId != null && audioTrack?.id != request.preferredAudioTrackId) {
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, "The requested audio track is unavailable.")
        }
        if (request.preferredSubtitleTrackId != null && subtitleTrack?.id != request.preferredSubtitleTrackId) {
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, "The requested subtitle track is unavailable.")
        }
        if (request.subtitleHandling == SubtitleHandling.BURN_IN) {
            if (subtitleTrack == null || subtitleTrack.isImageBased) {
                throw MediaBridgeException(
                    MediaBridgeErrorCode.UNSUPPORTED_REQUEST,
                    "Subtitle burn-in currently accepts text ASS, SSA, SRT, WebVTT, or mov_text tracks only.",
                )
            }
            if (videoTrack.colorInfo.dynamicRange != DynamicRangeFormat.SDR) {
                throw MediaBridgeException(
                    MediaBridgeErrorCode.UNSUPPORTED_REQUEST,
                    "Subtitle burn-in refuses HDR, HLG, Dolby Vision, and ambiguous color signals until the " +
                        "controlled 10-bit compositor is available.",
                )
            }
        }
        val outputInfo =
            MediaOutputInfo(
                videoHandling = request.videoHandling,
                audioHandling = request.audioHandling,
                subtitleHandling = request.subtitleHandling,
                selectedVideoTrackId = videoTrack.id,
                selectedAudioTrackId = audioTrack?.id,
                selectedSubtitleTrackId = subtitleTrack?.id,
                inputColorInfo = videoTrack.colorInfo,
                outputColorInfo =
                    if (request.subtitleHandling == SubtitleHandling.BURN_IN) {
                        SDR_BT709_COLOR_INFO
                    } else {
                        videoTrack.colorInfo
                    },
            )
        return DesktopFfmpegSession(runtime, input, request, outputInfo)
    }

    private fun requireLocalUnencryptedInput(input: MediaInput) {
        val reason =
            when {
                input.kind != MediaInputKind.FILE -> "The selected desktop runtime accepts local file inputs only."
                input.isLive -> "Live inputs are not supported by the selected desktop runtime."
                input.isEncrypted -> "Encrypted and DRM-protected inputs are outside this bridge."
                input.requestHeaders.isNotEmpty() -> "Request headers are only supported for remote inputs."
                else -> null
            }
        if (reason != null) {
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_INPUT, reason)
        }
    }

    private fun unsupportedReason(
        input: MediaInput,
        request: BridgeRequest,
    ): String? =
        when {
            input.kind != MediaInputKind.FILE -> "The selected desktop runtime accepts local file inputs only."
            input.isLive -> "Live inputs are not supported by the selected desktop runtime."
            input.isEncrypted -> "Encrypted and DRM-protected inputs are outside this bridge."
            input.requestHeaders.isNotEmpty() -> "Request headers are only supported for remote inputs."
            request.output != BridgeOutput.CMAF_FRAGMENT_STREAM ->
                "The selected desktop driver currently emits a CMAF fragment stream."
            request.subtitleHandling == SubtitleHandling.BURN_IN && !capabilities.canBurnSubtitles ->
                "The selected runtime does not include the optional libass subtitle burn-in pipeline."
            request.subtitleHandling == SubtitleHandling.BURN_IN && request.videoHandling != VideoHandling.TRANSCODE_TO_SDR ->
                "Subtitle burn-in requires explicit TRANSCODE_TO_SDR video handling."
            request.subtitleHandling == SubtitleHandling.OMIT && request.videoHandling != VideoHandling.COPY ->
                "The selected runtime copies compressed video unless subtitle burn-in is requested."
            request.audioHandling !in setOf(AudioHandling.OMIT, AudioHandling.COPY) ->
                "The selected runtime currently copies or omits audio."
            request.subtitleHandling == SubtitleHandling.BURN_IN && request.preferredSubtitleTrackId == null ->
                "Subtitle burn-in requires an explicitly selected subtitle track."
            request.dolbyVisionHandling != DolbyVisionHandling.PRESERVE ->
                "Dolby Vision conversion requires the separate optional converter module."
            else -> null
        }

    public companion object {
        private val SDR_BT709_COLOR_INFO =
            VideoColorInfo(
                dynamicRange = DynamicRangeFormat.SDR,
                bitDepth = 8,
                range = ColorRange.LIMITED,
                primaries = ColorPrimaries.BT709,
                transfer = ColorTransfer.BT709,
                matrix = ColorMatrix.BT709,
            )

        private val defaultInstance: BundledFfmpegNativeDriver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

        /** Loads the packaged runtime once and reuses it across probes, seeks, and playback sessions. */
        @JvmStatic
        public fun loadDefault(): BundledFfmpegNativeDriver = defaultInstance

        /** Loads and verifies the runtime before any media locator is opened. */
        @JvmStatic
        @JvmOverloads
        public fun load(
            replacementDirectory: Path? = null,
            extractionParentDirectory: Path? = null,
            classLoader: ClassLoader =
                Thread.currentThread().contextClassLoader
                    ?: BundledFfmpegNativeDriver::class.java.classLoader,
        ): BundledFfmpegNativeDriver =
            BundledFfmpegNativeDriver(
                DesktopRuntimeLoader.load(
                    replacementDirectory = replacementDirectory,
                    extractionParentDirectory = extractionParentDirectory,
                    classLoader = classLoader,
                ),
            )

        /**
         * Loads a runtime according to an explicit source policy.
         *
         * Preference policies fall back only when the preferred source has no
         * manifest. A present but technically invalid runtime is rejected
         * instead of being silently bypassed. KMediaBridge's LGPL distribution
         * gate applies to the bundled payload; external licensing is managed by
         * the caller and reported through [FfmpegRuntimeInfo].
         */
        @JvmStatic
        @JvmOverloads
        public fun load(
            runtimeSelection: FfmpegRuntimeSelection,
            extractionParentDirectory: Path? = null,
            classLoader: ClassLoader =
                Thread.currentThread().contextClassLoader
                    ?: BundledFfmpegNativeDriver::class.java.classLoader,
        ): BundledFfmpegNativeDriver =
            BundledFfmpegNativeDriver(
                DesktopRuntimeLoader.load(
                    runtimeSelection = runtimeSelection,
                    extractionParentDirectory = extractionParentDirectory,
                    classLoader = classLoader,
                ),
            )
    }
}

private sealed interface SessionState {
    data class Active(
        val positionUs: Long,
        val generation: Long,
    ) : SessionState

    data object Closed : SessionState
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DesktopFfmpegSession(
    private val runtime: LoadedFfmpegRuntime,
    private val input: MediaInput,
    private val request: BridgeRequest,
    private val outputInfo: MediaOutputInfo,
) : MediaBridgeSession {
    private val state = MutableStateFlow<SessionState>(SessionState.Active(positionUs = 0L, generation = 0L))
    private val collectionStarted = AtomicBoolean(false)
    private val activeCancellation = AtomicReference<AtomicBoolean?>(null)

    override val events: Flow<MediaBridgeEvent> =
        flow {
            if (!collectionStarted.compareAndSet(false, true)) {
                throw MediaBridgeException(
                    MediaBridgeErrorCode.INTERNAL,
                    "A media bridge session supports one event collector.",
                )
            }
            emit(MediaBridgeEvent.OutputConfigured(outputInfo))
            emitAll(
                state
                    .takeWhile { it is SessionState.Active }
                    .flatMapLatest { streamAt(it as SessionState.Active) },
            )
        }

    override suspend fun seekTo(positionUs: Long) {
        require(positionUs >= 0L) { "Seek position cannot be negative." }
        state.update { current ->
            when (current) {
                is SessionState.Active ->
                    SessionState.Active(
                        positionUs = positionUs,
                        generation = current.generation + 1L,
                    )
                SessionState.Closed ->
                    throw MediaBridgeException(MediaBridgeErrorCode.CANCELLED, "The media bridge session is closed.")
            }
        }
    }

    override suspend fun close() {
        state.value = SessionState.Closed
        activeCancellation.getAndSet(null)?.set(true)
    }

    private fun streamAt(active: SessionState.Active): Flow<MediaBridgeEvent> =
        channelFlow {
            if (active.generation > 0L || active.positionUs > 0L) {
                send(MediaBridgeEvent.Discontinuity(active.positionUs))
            }

            val cancelled = AtomicBoolean(false)
            activeCancellation.getAndSet(cancelled)?.set(true)
            val framer = FragmentedMp4Framer(active.positionUs, request.fragmentDurationUs)

            val nativeJob =
                launch(Dispatchers.IO) {
                    try {
                        val nativeContext = currentCoroutineContext()
                        val consumeBytes: (ByteArray) -> Boolean = { bytes ->
                            if (cancelled.get() || !nativeContext.isActive) {
                                false
                            } else {
                                try {
                                    val framed = framer.accept(bytes)
                                    runBlocking {
                                        framed.forEach { send(it) }
                                    }
                                    true
                                } catch (_: CancellationException) {
                                    cancelled.set(true)
                                    false
                                }
                            }
                        }
                        if (outputInfo.subtitleHandling == SubtitleHandling.BURN_IN) {
                            runtime.burnSubtitlesFragmentedMp4(
                                inputLocator = input.locator,
                                fragmentDurationUs = request.fragmentDurationUs,
                                startTimeUs = active.positionUs,
                                preferredVideoTrackId = outputInfo.selectedVideoTrackId ?: -1,
                                preferredAudioTrackId = outputInfo.selectedAudioTrackId ?: -2,
                                preferredSubtitleTrackId = outputInfo.selectedSubtitleTrackId ?: -1,
                                consumer = consumeBytes,
                            )
                        } else {
                            runtime.remuxFragmentedMp4(
                                inputLocator = input.locator,
                                fragmentDurationUs = request.fragmentDurationUs,
                                startTimeUs = active.positionUs,
                                preferredVideoTrackId = outputInfo.selectedVideoTrackId ?: -1,
                                preferredAudioTrackId = outputInfo.selectedAudioTrackId ?: -2,
                                consumer = consumeBytes,
                            )
                        }

                        if (!cancelled.get()) {
                            framer.finish().forEach { send(it) }
                            send(MediaBridgeEvent.EndOfStream)
                        }
                    } finally {
                        cancelled.set(true)
                        activeCancellation.compareAndSet(cancelled, null)
                    }
                }
            nativeJob.join()
        }
}
