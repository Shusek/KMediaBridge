// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.VideoCodec
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class FfmpegHlsVideoOutputPolicyTest {
    @Test
    fun forceSdrDoesNotReencodeConfirmedSdr() {
        assertEquals(
            VideoHandling.COPY,
            request(FfmpegHlsVideoOutputPolicy.FORCE_SDR).resolveVideoHandling(probe(DynamicRangeFormat.SDR)),
        )
    }

    @Test
    fun forceSdrSelectsControlledToneMapperForHlg() {
        assertEquals(
            VideoHandling.TONE_MAP_TO_SDR,
            request(FfmpegHlsVideoOutputPolicy.FORCE_SDR).resolveVideoHandling(probe(DynamicRangeFormat.HLG)),
        )
    }

    @Test
    fun preserveSourceKeepsHlgCompressedSamples() {
        assertEquals(
            VideoHandling.COPY,
            request(FfmpegHlsVideoOutputPolicy.PRESERVE_SOURCE).resolveVideoHandling(probe(DynamicRangeFormat.HLG)),
        )
    }

    @Test
    fun ambiguousSourceIsSentToStrictValidationInsteadOfBeingCopiedAsSdr() {
        assertEquals(
            VideoHandling.TONE_MAP_TO_SDR,
            request(FfmpegHlsVideoOutputPolicy.FORCE_SDR).resolveVideoHandling(probe(DynamicRangeFormat.UNKNOWN)),
        )
    }

    private fun request(policy: FfmpegHlsVideoOutputPolicy): FfmpegHlsPlaybackRequest =
        FfmpegHlsPlaybackRequest(
            input = MediaInput("/tmp/movie.mkv", MediaInputKind.FILE),
            videoOutputPolicy = policy,
        )

    private fun probe(dynamicRange: DynamicRangeFormat): MediaProbe =
        MediaProbe(
            container = MediaContainer.MATROSKA,
            durationUs = 1_000_000L,
            tracks =
                listOf(
                    VideoTrackInfo(
                        id = 0,
                        language = null,
                        codec = if (dynamicRange == DynamicRangeFormat.SDR) VideoCodec.AVC else VideoCodec.HEVC,
                        profile = null,
                        level = null,
                        width = 1920,
                        height = 1080,
                        frameRate = 24.0,
                        colorInfo =
                            VideoColorInfo(
                                dynamicRange = dynamicRange,
                                bitDepth = if (dynamicRange == DynamicRangeFormat.SDR) 8 else 10,
                                range = ColorRange.LIMITED,
                                primaries =
                                    if (dynamicRange == DynamicRangeFormat.SDR) {
                                        ColorPrimaries.BT709
                                    } else {
                                        ColorPrimaries.BT2020
                                    },
                                transfer =
                                    when (dynamicRange) {
                                        DynamicRangeFormat.HLG -> ColorTransfer.HLG
                                        DynamicRangeFormat.SDR -> ColorTransfer.BT709
                                        else -> ColorTransfer.PQ
                                    },
                                matrix =
                                    if (dynamicRange == DynamicRangeFormat.SDR) {
                                        ColorMatrix.BT709
                                    } else {
                                        ColorMatrix.BT2020_NCL
                                    },
                            ),
                    ),
                ),
        )
}
