// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import android.os.Build
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
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.MediaOutputInfo
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import io.github.shusek.kmediaffmpeg.runtime.KMediaFfmpegRuntime
import io.github.shusek.kmediaffmpeg.runtime.RuntimeSource as SharedRuntimeSource
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
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Selects the process-wide native FFmpeg runtime before its first Android use. */
public sealed interface AndroidFfmpegRuntimeSelection {
    /** Loads the optional `kmedia-bridge-ffmpeg-runtime-android` AAR payload. */
    public data object Bundled : AndroidFfmpegRuntimeSelection

    /**
     * Loads a replacement KMediaBridge ABI from an application-controlled directory.
     * Its effective license, including possible GPL obligations, belongs to the caller.
     */
    public class ExternalDirectory(
        public val rootDirectory: File,
    ) : AndroidFfmpegRuntimeSelection {
        init {
            require(rootDirectory.isDirectory) { "The external Android FFmpeg runtime directory does not exist." }
        }
    }
}

/** Android driver for the optional in-process FFmpeg/MediaCodec runtime. */
public class AndroidFfmpegNativeDriver private constructor(
    private val runtime: LoadedAndroidFfmpegRuntime,
) : FfmpegNativeDriver {
    override val runtimeInfo: FfmpegRuntimeInfo = runtime.runtimeInfo

    override val capabilities: BridgeCapabilities = ANDROID_CAPABILITIES

    override suspend fun evaluate(
        input: MediaInput,
        request: BridgeRequest,
    ): BridgeSupport {
        AndroidFfmpegRequestRules.unsupportedReason(input, request)?.let { return BridgeSupport.Unsupported(it) }
        if (request.videoHandling == VideoHandling.TONE_MAP_TO_SDR) {
            val probe =
                runCatching { probe(input) }.getOrElse { failure ->
                    return BridgeSupport.Unsupported(failure.message ?: "The HDR input could not be probed safely.")
                }
            val video =
                selectVideoTrack(probe, request.preferredVideoTrackId)
                    ?: return BridgeSupport.Unsupported("The requested HDR video track is unavailable.")
            AndroidFfmpegRequestRules.unsupportedToneMapColor(video.colorInfo)?.let {
                return BridgeSupport.Unsupported(it)
            }
            return BridgeSupport.Supported(
                confidence = 85,
                reason = "The Android runtime can decode PQ/HLG, apply the controlled BT.2020 transform, and emit BT.709 CMAF.",
            )
        }
        return BridgeSupport.Supported(
            confidence = 90,
            reason = "The Android runtime can copy the selected local video into a bounded CMAF fragment stream.",
        )
    }

    override suspend fun probe(input: MediaInput): MediaProbe {
        AndroidFfmpegRequestRules.requireLocalUnencryptedInput(input)
        val document =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                AndroidFfmpegNative.probeJsonBytes(input.locator).decodeToString()
            }
        return NativeProbeParser.parse(document)
    }

    override suspend fun open(
        input: MediaInput,
        request: BridgeRequest,
    ): MediaBridgeSession {
        AndroidFfmpegRequestRules.unsupportedReason(input, request)?.let { reason ->
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, reason)
        }
        val probe = probe(input)
        val videoTrack =
            selectVideoTrack(probe, request.preferredVideoTrackId)
                ?: throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_INPUT, "No requested video track is available.")
        if (request.videoHandling == VideoHandling.TONE_MAP_TO_SDR) {
            AndroidFfmpegRequestRules.unsupportedToneMapColor(videoTrack.colorInfo)?.let { reason ->
                throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, reason)
            }
        }
        val audioTracks = probe.tracks.filterIsInstance<AudioTrackInfo>()
        val audioTrack =
            if (request.audioHandling == AudioHandling.OMIT) {
                null
            } else {
                request.preferredAudioTrackId
                    ?.let { requested -> audioTracks.firstOrNull { it.id == requested } }
                    ?: audioTracks.firstOrNull(AudioTrackInfo::isDefault)
                    ?: audioTracks.firstOrNull()
            }
        if (request.preferredAudioTrackId != null && audioTrack?.id != request.preferredAudioTrackId) {
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_REQUEST, "The requested audio track is unavailable.")
        }
        val output =
            MediaOutputInfo(
                videoHandling = request.videoHandling,
                audioHandling = request.audioHandling,
                subtitleHandling = SubtitleHandling.OMIT,
                selectedVideoTrackId = videoTrack.id,
                selectedAudioTrackId = audioTrack?.id,
                selectedSubtitleTrackId = null,
                inputColorInfo = videoTrack.colorInfo,
                outputColorInfo =
                    if (request.videoHandling == VideoHandling.TONE_MAP_TO_SDR) {
                        SDR_BT709_COLOR_INFO
                    } else {
                        videoTrack.colorInfo
                    },
            )
        return AndroidFfmpegSession(input, request, output)
    }

    public companion object {
        /** Loads and verifies one process-wide runtime before opening any locator. */
        @JvmStatic
        @JvmOverloads
        public fun load(
            selection: AndroidFfmpegRuntimeSelection = AndroidFfmpegRuntimeSelection.Bundled,
            classLoader: ClassLoader =
                Thread.currentThread().contextClassLoader
                    ?: AndroidFfmpegNativeDriver::class.java.classLoader,
        ): AndroidFfmpegNativeDriver = AndroidFfmpegNativeDriver(AndroidFfmpegRuntimeLoader.load(selection, classLoader))
    }
}

internal object AndroidFfmpegRequestRules {
    fun requireLocalUnencryptedInput(input: MediaInput) {
        unsupportedInputReason(input)?.let { reason ->
            throw MediaBridgeException(MediaBridgeErrorCode.UNSUPPORTED_INPUT, reason)
        }
    }

    fun unsupportedReason(
        input: MediaInput,
        request: BridgeRequest,
    ): String? =
        unsupportedInputReason(input)
            ?: when {
                request.output != BridgeOutput.CMAF_FRAGMENT_STREAM ->
                    "The Android runtime currently emits a CMAF fragment stream."
                request.videoHandling !in setOf(VideoHandling.COPY, VideoHandling.TONE_MAP_TO_SDR) ->
                    "The Android runtime copies video or performs explicit HDR-to-SDR tone mapping."
                request.audioHandling !in setOf(AudioHandling.OMIT, AudioHandling.COPY) ->
                    "The Android runtime currently copies or omits audio."
                request.subtitleHandling != SubtitleHandling.OMIT ->
                    "Subtitle burn-in is a separate optional pipeline and is not present in this Android runtime."
                request.preferredSubtitleTrackId != null -> "The Android runtime did not accept a subtitle track."
                request.dolbyVisionHandling != DolbyVisionHandling.PRESERVE ->
                    "Dolby Vision profile conversion requires the separate optional converter module."
                else -> null
            }

    fun unsupportedToneMapColor(color: VideoColorInfo): String? =
        when {
            color.dynamicRange == DynamicRangeFormat.DOLBY_VISION || color.dolbyVision != null ->
                "Dolby Vision must use the profile-aware converter before SDR tone mapping."
            color.dynamicRange !in
                setOf(
                    DynamicRangeFormat.HDR10,
                    DynamicRangeFormat.HDR10_PLUS,
                    DynamicRangeFormat.HLG,
                )
            -> "The selected video is not explicitly identified as HDR10, HDR10+, or HLG."
            color.primaries != ColorPrimaries.BT2020 -> "The HDR input is not explicitly tagged with BT.2020 primaries."
            color.transfer !in setOf(ColorTransfer.PQ, ColorTransfer.HLG) ->
                "The HDR input has no supported explicit PQ or HLG transfer."
            color.matrix != ColorMatrix.BT2020_NCL ->
                "The HDR input does not use the supported BT.2020 non-constant-luminance matrix."
            color.range !in setOf(ColorRange.LIMITED, ColorRange.FULL) ->
                "The HDR input has no unambiguous limited or full color range."
            else -> null
        }

    private fun unsupportedInputReason(input: MediaInput): String? =
        when {
            input.kind != MediaInputKind.FILE ->
                "The current Android native runtime accepts local files only; remote/custom I/O remains a separate bounded adapter."
            input.isLive -> "Live inputs are not converted by the Android native runtime."
            input.isEncrypted -> "Encrypted and DRM-protected inputs are outside this bridge."
            input.requestHeaders.isNotEmpty() -> "Request headers are only valid for a remote input adapter."
            else -> null
        }
}

private data class LoadedAndroidFfmpegRuntime(
    val runtimeInfo: FfmpegRuntimeInfo,
)

private data class AndroidRuntimeManifest(
    val properties: Properties,
    val abi: String,
    val rootDirectory: File?,
    val origin: FfmpegRuntimeOrigin,
) {
    val sharedRuntimeId: String = required("sharedRuntimeId")
    val ffmpegVersion: String = required("ffmpegVersion")
    val ffmpegLicenseSpdx: String = required("ffmpegLicenseSpdx")
    val sourceArchiveUrl: String = required("ffmpegSourceArchiveUrl")
    val sourceArchiveSha256: String = requiredSha256("ffmpegSourceArchiveSha256")
    val buildRecipeUrl: String = required("buildRecipeUrl")
    val buildRecipeRevision: String = required("buildRecipeRevision")
    val exactCorrespondingSourceAvailable: Boolean = requiredBoolean("exactCorrespondingSourceAvailable")
    val dynamicLinkingVerified: Boolean = requiredBoolean("dynamicLinkingVerified")
    val bridgeSha256: String = requiredSha256("${abiPrefix()}libkmediabridge.so.sha256")

    init {
        require(required("schemaVersion") == "1") { "The Android FFmpeg runtime manifest schema is unsupported." }
        require(required("abiVersion") == SUPPORTED_ABI.toString()) { "The Android FFmpeg runtime ABI is unsupported." }
        require(requiredBoolean("available")) { "The optional Android FFmpeg native payload is not present." }
        require(requiredBoolean("feature.hdrToSdrToneMap")) {
            "The Android runtime manifest does not declare controlled HDR-to-SDR conversion."
        }
        require(!requiredBoolean("feature.subtitleBurnIn")) {
            "The Android HDR runtime unexpectedly declares subtitle burn-in."
        }
        require(dynamicLinkingVerified) { "The Android runtime has no verified dynamic FFmpeg boundary." }
        require(sharedRuntimeId.matches(Regex("kmediaffmpeg-8\\.1\\.2-ass-0\\.17\\.4-[0-9a-f]{16}"))) {
            "The Android client manifest has an invalid shared runtime ID."
        }
    }

    fun verifyAndLoad() {
        if (rootDirectory == null) {
            System.loadLibrary("kmediabridge")
            return
        }
        val directory = rootDirectory.resolve(abi)
        LIBRARY_FILE_NAMES.forEach { name ->
            val library = directory.resolve(name)
            require(library.isFile) { "The external Android FFmpeg runtime is missing $name for $abi." }
            val expected = requiredSha256("${abiPrefix()}$name.sha256")
            require(sha256(library) == expected) { "An external Android FFmpeg runtime library failed its SHA-256 check." }
        }
        System.load(directory.resolve("libkmediabridge.so").absolutePath)
    }

    private fun abiPrefix(): String {
        val count = required("abi.count").toIntOrNull() ?: 0
        val index =
            (0 until count).firstOrNull { properties.getProperty("abi.$it.name") == abi }
                ?: error("The Android FFmpeg runtime has no payload for ABI $abi.")
        return "abi.$index."
    }

    private fun required(name: String): String =
        properties.getProperty(name)?.takeIf(String::isNotBlank)
            ?: error("The Android FFmpeg runtime manifest is missing $name.")

    private fun requiredBoolean(name: String): Boolean =
        required(name).toBooleanStrictOrNull()
            ?: error("The Android FFmpeg runtime manifest has an invalid $name value.")

    private fun requiredSha256(name: String): String =
        required(name).also { value ->
            require(SHA256.matches(value)) { "The Android FFmpeg runtime manifest has an invalid $name hash." }
        }
}

private object AndroidFfmpegRuntimeLoader {
    private val lock = Any()
    private var loaded: Pair<String, LoadedAndroidFfmpegRuntime>? = null

    fun load(
        selection: AndroidFfmpegRuntimeSelection,
        classLoader: ClassLoader,
    ): LoadedAndroidFfmpegRuntime =
        synchronized(lock) {
            val key =
                when (selection) {
                    AndroidFfmpegRuntimeSelection.Bundled -> "bundled"
                    is AndroidFfmpegRuntimeSelection.ExternalDirectory ->
                        "external:${selection.rootDirectory.canonicalPath}"
                }
            loaded?.let { (loadedKey, runtime) ->
                if (loadedKey != key) {
                    throw MediaBridgeException(
                        MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                        "A different Android FFmpeg runtime is already loaded in this process.",
                    )
                }
                return@synchronized runtime
            }
            val manifest = readManifest(selection, classLoader)
            val sharedRuntime =
                KMediaFfmpegRuntime.currentOrNull()
                    ?: KMediaFfmpegRuntime.initialize(SharedRuntimeSource.bundled())
            if (sharedRuntime.runtimeId() != manifest.sharedRuntimeId) {
                rejectRuntime("The Android KMediaBridge client targets a different KMediaFfmpegRuntime ID.")
            }
            try {
                manifest.verifyAndLoad()
            } catch (failure: Throwable) {
                throw MediaBridgeException(
                    MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                    failure.message ?: "The Android FFmpeg runtime could not be loaded.",
                    failure,
                )
            }
            val actualAbi = AndroidFfmpegNative.abiVersion()
            val actualVersion = AndroidFfmpegNative.ffmpegVersionBytes().decodeToString()
            val actualLicense = AndroidFfmpegNative.ffmpegLicenseBytes().decodeToString()
            val actualConfiguration = AndroidFfmpegNative.ffmpegConfigurationBytes().decodeToString()
            val actualFeatures = AndroidFfmpegNative.runtimeFeaturesJsonBytes().decodeToString()
            if (actualAbi != SUPPORTED_ABI ||
                actualVersion != manifest.ffmpegVersion ||
                actualVersion != sharedRuntime.componentVersions()["ffmpeg"]
            ) {
                rejectRuntime("The loaded Android FFmpeg identity differs from its manifest.")
            }
            if (actualFeatures != EXPECTED_FEATURES) {
                rejectRuntime("The loaded Android FFmpeg feature set differs from its manifest.")
            }
            val configureArguments = actualConfiguration.split(Regex("\\s+")).filter(String::isNotBlank)
            if (manifest.origin == FfmpegRuntimeOrigin.BUNDLED) {
                if (!actualLicense.contains("LGPL", ignoreCase = true) ||
                    manifest.ffmpegLicenseSpdx != "LGPL-2.1-or-later" ||
                    !REQUIRED_BUNDLED_FLAGS.all(configureArguments::contains) ||
                    FORBIDDEN_BUNDLED_FLAGS.any(configureArguments::contains)
                ) {
                    rejectRuntime("The bundled Android FFmpeg runtime failed its LGPL-only identity check.")
                }
            }
            val info =
                FfmpegRuntimeInfo(
                    ffmpegVersion = actualVersion,
                    ffmpegLicenseSpdx = manifest.ffmpegLicenseSpdx,
                    ffmpegReportedLicense = actualLicense,
                    configureArguments = configureArguments,
                    ffmpegSourceArchiveUrl = manifest.sourceArchiveUrl,
                    ffmpegSourceArchiveSha256 = manifest.sourceArchiveSha256,
                    nativeArtifactSha256 = manifest.bridgeSha256,
                    buildRecipeUrl = manifest.buildRecipeUrl,
                    buildRecipeRevision = manifest.buildRecipeRevision,
                    exactCorrespondingSourceAvailable = manifest.exactCorrespondingSourceAvailable,
                    dynamicLinkingVerified = manifest.dynamicLinkingVerified,
                    origin = manifest.origin,
                    sharedRuntimeId = sharedRuntime.runtimeId(),
                    sharedRuntimeConfigurationSha256 = sharedRuntime.configurationSha256(),
                )
            FfmpegComplianceVerifier.requireAllowedByDistributionPolicy(info)
            LoadedAndroidFfmpegRuntime(info).also { runtime -> loaded = key to runtime }
        }

    private fun readManifest(
        selection: AndroidFfmpegRuntimeSelection,
        classLoader: ClassLoader,
    ): AndroidRuntimeManifest {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        return when (selection) {
            AndroidFfmpegRuntimeSelection.Bundled -> {
                val properties =
                    classLoader.getResourceAsStream(BUNDLED_MANIFEST)?.use { input ->
                        Properties().apply { load(input) }
                    } ?: throw MediaBridgeException(
                        MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                        "The optional Android FFmpeg runtime AAR and its manifest are not on the classpath.",
                    )
                AndroidRuntimeManifest(properties, abi, null, FfmpegRuntimeOrigin.BUNDLED)
            }
            is AndroidFfmpegRuntimeSelection.ExternalDirectory -> {
                val manifestFile = selection.rootDirectory.resolve(EXTERNAL_MANIFEST)
                require(manifestFile.isFile) { "The external Android FFmpeg runtime manifest is missing." }
                val properties = manifestFile.inputStream().use { input -> Properties().apply { load(input) } }
                AndroidRuntimeManifest(properties, abi, selection.rootDirectory, FfmpegRuntimeOrigin.EXTERNAL_DIRECTORY)
            }
        }
    }

    private fun rejectRuntime(message: String): Nothing =
        throw MediaBridgeException(MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME, message)
}

internal object AndroidFfmpegNative {
    external fun abiVersion(): Int

    external fun ffmpegVersionBytes(): ByteArray

    external fun ffmpegLicenseBytes(): ByteArray

    external fun ffmpegConfigurationBytes(): ByteArray

    external fun runtimeFeaturesJsonBytes(): ByteArray

    external fun probeJsonBytes(inputLocator: String): ByteArray

    external fun remuxFragmentedMp4Stream(
        inputLocator: String,
        fragmentDurationUs: Long,
        startTimeUs: Long,
        preferredVideoTrackId: Int,
        preferredAudioTrackId: Int,
        consumer: AndroidNativeByteConsumer,
    ): Int

    external fun toneMapHdrToSdrFragmentedMp4Stream(
        inputLocator: String,
        fragmentDurationUs: Long,
        startTimeUs: Long,
        preferredVideoTrackId: Int,
        preferredAudioTrackId: Int,
        consumer: AndroidNativeByteConsumer,
    ): Int
}

internal class AndroidNativeByteConsumer(
    private val delegate: (ByteArray) -> Boolean,
) {
    @Suppress("unused")
    fun accept(bytes: ByteArray): Boolean = delegate(bytes)
}

private sealed interface AndroidSessionState {
    data class Active(
        val positionUs: Long,
        val generation: Long,
    ) : AndroidSessionState

    data object Closed : AndroidSessionState
}

@OptIn(ExperimentalCoroutinesApi::class)
private class AndroidFfmpegSession(
    private val input: MediaInput,
    private val request: BridgeRequest,
    private val outputInfo: MediaOutputInfo,
) : MediaBridgeSession {
    private val state = MutableStateFlow<AndroidSessionState>(AndroidSessionState.Active(0L, 0L))
    private val collectionStarted = AtomicBoolean(false)
    private val activeCancellation = AtomicReference<AtomicBoolean?>(null)

    override val events: Flow<MediaBridgeEvent> =
        flow {
            if (!collectionStarted.compareAndSet(false, true)) {
                throw MediaBridgeException(MediaBridgeErrorCode.INTERNAL, "A media bridge session supports one event collector.")
            }
            emit(MediaBridgeEvent.OutputConfigured(outputInfo))
            emitAll(
                state
                    .takeWhile { it is AndroidSessionState.Active }
                    .flatMapLatest { streamAt(it as AndroidSessionState.Active) },
            )
        }

    override suspend fun seekTo(positionUs: Long) {
        require(positionUs >= 0L) { "Seek position cannot be negative." }
        state.update { current ->
            when (current) {
                is AndroidSessionState.Active ->
                    AndroidSessionState.Active(positionUs, current.generation + 1L)
                AndroidSessionState.Closed ->
                    throw MediaBridgeException(MediaBridgeErrorCode.CANCELLED, "The media bridge session is closed.")
            }
        }
    }

    override suspend fun close() {
        state.value = AndroidSessionState.Closed
        activeCancellation.getAndSet(null)?.set(true)
    }

    private fun streamAt(active: AndroidSessionState.Active): Flow<MediaBridgeEvent> =
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
                        val consumer =
                            AndroidNativeByteConsumer { bytes ->
                                if (cancelled.get() || !nativeContext.isActive) {
                                    false
                                } else {
                                    try {
                                        val framed = framer.accept(bytes)
                                        runBlocking { framed.forEach { send(it) } }
                                        true
                                    } catch (_: CancellationException) {
                                        cancelled.set(true)
                                        false
                                    }
                                }
                            }
                        try {
                            if (outputInfo.videoHandling == VideoHandling.TONE_MAP_TO_SDR) {
                                AndroidFfmpegNative.toneMapHdrToSdrFragmentedMp4Stream(
                                    input.locator,
                                    request.fragmentDurationUs,
                                    active.positionUs,
                                    outputInfo.selectedVideoTrackId ?: -1,
                                    outputInfo.selectedAudioTrackId ?: -2,
                                    consumer,
                                )
                            } else {
                                AndroidFfmpegNative.remuxFragmentedMp4Stream(
                                    input.locator,
                                    request.fragmentDurationUs,
                                    active.positionUs,
                                    outputInfo.selectedVideoTrackId ?: -1,
                                    outputInfo.selectedAudioTrackId ?: -2,
                                    consumer,
                                )
                            }
                        } catch (failure: IllegalStateException) {
                            throw MediaBridgeException(
                                MediaBridgeErrorCode.CONVERSION_FAILED,
                                failure.message ?: "The Android native media conversion failed.",
                                failure,
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

private fun selectVideoTrack(
    probe: MediaProbe,
    requestedId: Int?,
): VideoTrackInfo? {
    val videos = probe.tracks.filterIsInstance<VideoTrackInfo>()
    return requestedId?.let { requested -> videos.firstOrNull { it.id == requested } } ?: videos.firstOrNull()
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val ANDROID_CAPABILITIES =
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
        canToneMapToSdr = true,
        canConvertDolbyVisionProfile7 = false,
        supportsLiveInput = false,
        supportsEncryptedInput = false,
        supportsRemoteInput = false,
        canTranscodeVideo = true,
        canTranscodeAudio = false,
        canBurnSubtitles = false,
    )

private val SDR_BT709_COLOR_INFO =
    VideoColorInfo(
        dynamicRange = DynamicRangeFormat.SDR,
        bitDepth = 8,
        range = ColorRange.LIMITED,
        primaries = ColorPrimaries.BT709,
        transfer = ColorTransfer.BT709,
        matrix = ColorMatrix.BT709,
    )

private val REQUIRED_BUNDLED_FLAGS = setOf("--disable-gpl", "--disable-nonfree", "--disable-static", "--enable-shared")
private val FORBIDDEN_BUNDLED_FLAGS = setOf("--enable-gpl", "--enable-nonfree", "--enable-libx264", "--enable-libx265")
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val LIBRARY_FILE_NAMES =
    listOf(
        "libkmediabridge.so",
    )
private const val SUPPORTED_ABI = 4
private const val EXPECTED_FEATURES = "{\"subtitleBurnIn\":false,\"hdrToSdrToneMap\":true}"
private const val BUNDLED_MANIFEST = "META-INF/kmediabridge/android-client.properties"
private const val EXTERNAL_MANIFEST = "android-client.properties"
