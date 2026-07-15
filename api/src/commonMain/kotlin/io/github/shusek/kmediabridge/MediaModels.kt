// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge

/** Opaque source descriptor. Its locator must never be written to logs because it may contain signed URLs. */
public class MediaInput(
    public val locator: String,
    public val kind: MediaInputKind = MediaInputKind.URI,
    public val isLive: Boolean = false,
    public val isEncrypted: Boolean = false,
) {
    init {
        require(locator.isNotBlank()) { "A media locator cannot be blank." }
    }

    override fun equals(other: Any?): Boolean =
        other is MediaInput &&
            locator == other.locator &&
            kind == other.kind &&
            isLive == other.isLive &&
            isEncrypted == other.isEncrypted

    override fun hashCode(): Int {
        var result = locator.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + isLive.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        return result
    }

    override fun toString(): String =
        "MediaInput(locator=<redacted>, kind=$kind, isLive=$isLive, isEncrypted=$isEncrypted)"
}

public enum class MediaInputKind {
    URI,
    FILE,
    CUSTOM_IO,
}

public enum class MediaContainer {
    MATROSKA,
    MP4,
    FRAGMENTED_MP4,
    MPEG_TS,
    HLS,
    WEBM,
    UNKNOWN,
}

public enum class VideoCodec {
    AVC,
    HEVC,
    AV1,
    VP9,
    DOLBY_VISION,
    UNKNOWN,
}

public enum class DynamicRangeFormat {
    SDR,
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION,
    UNKNOWN,
}

public enum class ColorRange {
    LIMITED,
    FULL,
    UNKNOWN,
}

public enum class ColorPrimaries {
    BT709,
    BT2020,
    DISPLAY_P3,
    UNKNOWN,
}

public enum class ColorTransfer {
    BT709,
    SRGB,
    PQ,
    HLG,
    LINEAR,
    UNKNOWN,
}

public enum class ColorMatrix {
    BT709,
    BT2020_NCL,
    BT2020_CL,
    IDENTITY,
    UNKNOWN,
}

public data class Chromaticity(
    public val x: Double,
    public val y: Double,
)

public data class MasteringDisplayInfo(
    public val red: Chromaticity,
    public val green: Chromaticity,
    public val blue: Chromaticity,
    public val whitePoint: Chromaticity,
    public val minimumLuminanceNits: Double,
    public val maximumLuminanceNits: Double,
)

public data class ContentLightLevelInfo(
    public val maximumContentLightLevelNits: Int,
    public val maximumFrameAverageLightLevelNits: Int,
)

public data class DolbyVisionInfo(
    public val profile: Int,
    public val level: Int?,
    public val hasRpu: Boolean,
    public val hasEnhancementLayer: Boolean,
)

public data class VideoColorInfo(
    public val dynamicRange: DynamicRangeFormat,
    public val bitDepth: Int?,
    public val range: ColorRange,
    public val primaries: ColorPrimaries,
    public val transfer: ColorTransfer,
    public val matrix: ColorMatrix,
    public val masteringDisplay: MasteringDisplayInfo? = null,
    public val contentLightLevel: ContentLightLevelInfo? = null,
    public val hasHdr10PlusMetadata: Boolean = false,
    public val dolbyVision: DolbyVisionInfo? = null,
)

public sealed interface MediaTrackInfo {
    public val id: Int
    public val language: String?
}

public data class VideoTrackInfo(
    override val id: Int,
    override val language: String?,
    public val codec: VideoCodec,
    public val profile: Int?,
    public val level: Int?,
    public val width: Int?,
    public val height: Int?,
    public val frameRate: Double?,
    public val colorInfo: VideoColorInfo,
) : MediaTrackInfo

public data class AudioTrackInfo(
    override val id: Int,
    override val language: String?,
    public val codecName: String,
    public val channels: Int?,
    public val sampleRateHz: Int?,
) : MediaTrackInfo

public data class SubtitleTrackInfo(
    override val id: Int,
    override val language: String?,
    public val codecName: String,
    public val isImageBased: Boolean,
) : MediaTrackInfo

public data class MediaProbe(
    public val container: MediaContainer,
    public val durationUs: Long?,
    public val tracks: List<MediaTrackInfo>,
)

public enum class BridgeOutput {
    CMAF_FRAGMENT_STREAM,
    FRAGMENTED_MP4_FILE,
}

public enum class VideoHandling {
    COPY,
    TONE_MAP_TO_SDR,
}

public enum class DolbyVisionHandling {
    PRESERVE,
    CONVERT_PROFILE_7_TO_8_1,
    USE_HDR10_BASE_LAYER,
    REJECT,
}

public data class BridgeRequest(
    public val output: BridgeOutput = BridgeOutput.CMAF_FRAGMENT_STREAM,
    public val videoHandling: VideoHandling = VideoHandling.COPY,
    public val dolbyVisionHandling: DolbyVisionHandling = DolbyVisionHandling.PRESERVE,
    public val fragmentDurationUs: Long = 4_000_000L,
) {
    init {
        require(fragmentDurationUs > 0L) { "Fragment duration must be positive." }
    }
}

public data class MediaFragment(
    public val sequence: Long,
    public val presentationTimeUs: Long,
    public val durationUs: Long,
    public val isInitialization: Boolean,
    public val bytes: ByteArray,
)

public sealed interface MediaBridgeEvent {
    public data class Fragment(public val value: MediaFragment) : MediaBridgeEvent

    public data class Discontinuity(public val resumeTimeUs: Long) : MediaBridgeEvent

    public data object EndOfStream : MediaBridgeEvent
}
