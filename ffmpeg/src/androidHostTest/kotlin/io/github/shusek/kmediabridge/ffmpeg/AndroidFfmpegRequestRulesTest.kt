// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DolbyVisionInfo
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoHandling
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidFfmpegRequestRulesTest {
    @Test
    fun localCopyRequestIsAccepted() {
        assertNull(
            AndroidFfmpegRequestRules.unsupportedReason(
                MediaInput("/media/movie.mkv", MediaInputKind.FILE),
                BridgeRequest(),
            ),
        )
    }

    @Test
    fun remoteAndSubtitleRequestsFailClosed() {
        assertNotNull(
            AndroidFfmpegRequestRules.unsupportedReason(
                MediaInput("https://example.invalid/movie.mkv", MediaInputKind.URI),
                BridgeRequest(),
            ),
        )
        assertNotNull(
            AndroidFfmpegRequestRules.unsupportedReason(
                MediaInput("/media/movie.mkv", MediaInputKind.FILE),
                BridgeRequest(
                    videoHandling = VideoHandling.TRANSCODE_TO_SDR,
                    subtitleHandling = SubtitleHandling.BURN_IN,
                    preferredSubtitleTrackId = 2,
                ),
            ),
        )
    }

    @Test
    fun explicitHlgIsAcceptedForControlledToneMapping() {
        assertNull(AndroidFfmpegRequestRules.unsupportedToneMapColor(hdrColor(DynamicRangeFormat.HLG, ColorTransfer.HLG)))
    }

    @Test
    fun dolbyVisionAndAmbiguousRangeAreRejected() {
        assertNotNull(
            AndroidFfmpegRequestRules.unsupportedToneMapColor(
                hdrColor(
                    dynamicRange = DynamicRangeFormat.DOLBY_VISION,
                    transfer = ColorTransfer.PQ,
                    dolbyVision = DolbyVisionInfo(profile = 8, level = 6, hasRpu = true, hasEnhancementLayer = false),
                ),
            ),
        )
        assertNotNull(
            AndroidFfmpegRequestRules.unsupportedToneMapColor(
                hdrColor(DynamicRangeFormat.HDR10, ColorTransfer.PQ, range = ColorRange.UNKNOWN),
            ),
        )
    }

    private fun hdrColor(
        dynamicRange: DynamicRangeFormat,
        transfer: ColorTransfer,
        range: ColorRange = ColorRange.LIMITED,
        dolbyVision: DolbyVisionInfo? = null,
    ): VideoColorInfo =
        VideoColorInfo(
            dynamicRange = dynamicRange,
            bitDepth = 10,
            range = range,
            primaries = ColorPrimaries.BT2020,
            transfer = transfer,
            matrix = ColorMatrix.BT2020_NCL,
            dolbyVision = dolbyVision,
        )
}
