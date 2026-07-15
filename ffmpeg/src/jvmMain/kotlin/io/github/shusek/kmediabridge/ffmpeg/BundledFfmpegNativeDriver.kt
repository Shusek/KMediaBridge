// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.BridgeCapabilities
import io.github.shusek.kmediabridge.BridgeOutput
import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.BridgeSupport
import io.github.shusek.kmediabridge.DolbyVisionHandling
import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.VideoHandling
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

/**
 * A desktop JVM driver backed by the optional bundled FFmpeg runtime artifact.
 *
 * No FFmpeg executable or system installation is used. A replacement directory
 * may be supplied to exercise the dynamic-link replacement rights required by
 * the LGPL. That directory must contain its own `manifest.properties`.
 */
public class BundledFfmpegNativeDriver private constructor(
    private val runtime: LoadedFfmpegRuntime,
) : FfmpegNativeDriver {
    override val runtimeInfo: FfmpegRuntimeInfo = runtime.runtimeInfo

    override val capabilities: BridgeCapabilities =
        BridgeCapabilities(
            inputContainers =
                setOf(
                    MediaContainer.MATROSKA,
                    MediaContainer.WEBM,
                    MediaContainer.MP4,
                    MediaContainer.FRAGMENTED_MP4,
                    MediaContainer.MPEG_TS,
                ),
            outputs = setOf(BridgeOutput.CMAF_FRAGMENT_STREAM),
            canProbe = true,
            canCopyVideo = true,
            canToneMapToSdr = false,
            canConvertDolbyVisionProfile7 = false,
            supportsLiveInput = false,
            supportsEncryptedInput = false,
        )

    override suspend fun evaluate(
        input: MediaInput,
        request: BridgeRequest,
    ): BridgeSupport {
        val reason = unsupportedReason(input, request)
        return if (reason == null) {
            BridgeSupport.Supported(
                confidence = 90,
                reason = "The bundled LGPL FFmpeg runtime can remux this local input without re-encoding video.",
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
        return DesktopFfmpegSession(runtime, input, request)
    }

    private fun requireLocalUnencryptedInput(input: MediaInput) {
        val reason =
            when {
                input.kind != MediaInputKind.FILE -> "The bundled desktop runtime accepts local file inputs only."
                input.isLive -> "Live inputs are not supported by the bundled desktop runtime."
                input.isEncrypted -> "Encrypted and DRM-protected inputs are outside this bridge."
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
            input.kind != MediaInputKind.FILE -> "The bundled desktop runtime accepts local file inputs only."
            input.isLive -> "Live inputs are not supported by the bundled desktop runtime."
            input.isEncrypted -> "Encrypted and DRM-protected inputs are outside this bridge."
            request.output != BridgeOutput.CMAF_FRAGMENT_STREAM ->
                "The bundled desktop driver currently emits a CMAF fragment stream."
            request.videoHandling != VideoHandling.COPY ->
                "The bundled LGPL runtime copies compressed video and does not tone-map it."
            request.dolbyVisionHandling != DolbyVisionHandling.PRESERVE ->
                "Dolby Vision conversion requires the separate optional converter module."
            else -> null
        }

    public companion object {
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
    }
}

private sealed interface SessionState {
    data class Active(val positionUs: Long, val generation: Long) : SessionState

    data object Closed : SessionState
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DesktopFfmpegSession(
    private val runtime: LoadedFfmpegRuntime,
    private val input: MediaInput,
    private val request: BridgeRequest,
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
                        runtime.remuxFragmentedMp4(
                            inputLocator = input.locator,
                            fragmentDurationUs = request.fragmentDurationUs,
                            startTimeUs = active.positionUs,
                        ) { bytes ->
                            if (cancelled.get() || !nativeContext.isActive) {
                                return@remuxFragmentedMp4 false
                            }
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
