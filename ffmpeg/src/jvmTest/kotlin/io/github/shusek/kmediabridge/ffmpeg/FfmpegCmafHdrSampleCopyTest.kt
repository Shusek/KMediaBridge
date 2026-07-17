// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.MediaOutputInfo
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoCodec
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class FfmpegCmafHdrSampleCopyTest {
    @Test
    fun `recognises strict HEVC HDR10 and HLG sample-copy routes`() {
        assertEquals(FfmpegCmafHdrSampleCopy.HDR10, probe(DynamicRangeFormat.HDR10, ColorTransfer.PQ).signal())
        assertEquals(FfmpegCmafHdrSampleCopy.HLG, probe(DynamicRangeFormat.HLG, ColorTransfer.HLG).signal())
    }

    @Test
    fun `does not call ambiguous or non-HEVC output an HDR sample copy`() {
        assertEquals(
            FfmpegCmafHdrSampleCopy.NONE,
            probe(DynamicRangeFormat.HDR10, ColorTransfer.PQ, codec = VideoCodec.AV1).signal(),
        )
        assertEquals(
            FfmpegCmafHdrSampleCopy.NONE,
            probe(DynamicRangeFormat.HDR10, ColorTransfer.PQ, bitDepth = null).signal(),
        )
    }

    private fun probe(
        dynamicRange: DynamicRangeFormat,
        transfer: ColorTransfer,
        codec: VideoCodec = VideoCodec.HEVC,
        bitDepth: Int? = 10,
    ): MediaProbe {
        val color =
            VideoColorInfo(
                dynamicRange = dynamicRange,
                bitDepth = bitDepth,
                range = ColorRange.LIMITED,
                primaries = ColorPrimaries.BT2020,
                transfer = transfer,
                matrix = ColorMatrix.BT2020_NCL,
            )
        return MediaProbe(
            container = MediaContainer.MATROSKA,
            durationUs = 1_000_000L,
            tracks =
                listOf(
                    VideoTrackInfo(
                        id = 0,
                        language = null,
                        codec = codec,
                        profile = null,
                        level = null,
                        width = 3840,
                        height = 2160,
                        frameRate = 60.0,
                        colorInfo = color,
                    ),
                ),
        )
    }

    private fun MediaProbe.signal(): FfmpegCmafHdrSampleCopy {
        val video = tracks.single() as VideoTrackInfo
        return copiedHdrSignal(
            MediaOutputInfo(
                videoHandling = VideoHandling.COPY,
                audioHandling = AudioHandling.COPY,
                subtitleHandling = SubtitleHandling.OMIT,
                selectedVideoTrackId = video.id,
                selectedAudioTrackId = null,
                selectedSubtitleTrackId = null,
                inputColorInfo = video.colorInfo,
                outputColorInfo = video.colorInfo,
            ),
        )
    }
}
