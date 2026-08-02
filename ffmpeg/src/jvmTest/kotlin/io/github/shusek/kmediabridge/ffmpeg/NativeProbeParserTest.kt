// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.AudioTrackInfo
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.SubtitleTrackInfo
import io.github.shusek.kmediabridge.VideoCodec
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativeProbeParserTest {
    @Test
    fun parsesStaticHdrAndSelectableTrackMetadata() {
        val probe =
            NativeProbeParser.parse(
                """
                {
                  "format":"matroska,webm",
                  "durationUs":1000000,
                  "tracks":[
                    {
                      "type":"video","id":0,"codec":"hevc","profile":2,"level":153,
                      "width":3840,"height":2160,"bitDepth":10,"dynamicRange":"HDR10",
                      "colorRange":"tv","colorPrimaries":"bt2020","colorTransfer":"smpte2084",
                      "colorMatrix":"bt2020nc","frameRateNumerator":60000,"frameRateDenominator":1001,
                      "hasHdr10PlusMetadata":false,"dolbyVision":null,
                      "masteringDisplay":{
                        "redX":0.68,"redY":0.32,"greenX":0.265,"greenY":0.69,
                        "blueX":0.15,"blueY":0.06,"whiteX":0.3127,"whiteY":0.329,
                        "minimumLuminanceNits":0.005,"maximumLuminanceNits":1000.0
                      },
                      "contentLightLevel":{
                        "maximumContentLightLevelNits":1000,
                        "maximumFrameAverageLightLevelNits":400
                      },
                      "language":null,"title":"Main HDR","isDefault":true
                    },
                    {
                      "type":"audio","id":1,"codec":"aac","channels":6,"sampleRateHz":48000,
                      "bitrate":384000,"language":"eng","title":"Surround","isDefault":true
                    },
                    {
                      "type":"subtitle","id":2,"codec":"ass","isImageBased":false,
                      "language":"pol","title":"Polski","isDefault":false
                    }
                  ]
                }
                """.trimIndent(),
            )

        val video = probe.tracks.filterIsInstance<VideoTrackInfo>().single()
        assertEquals(DynamicRangeFormat.HDR10, video.colorInfo.dynamicRange)
        assertEquals(1_000.0, video.colorInfo.masteringDisplay?.maximumLuminanceNits)
        assertEquals(400, video.colorInfo.contentLightLevel?.maximumFrameAverageLightLevelNits)
        val audio = probe.tracks.filterIsInstance<AudioTrackInfo>().single()
        assertEquals("Surround", audio.title)
        assertEquals(384_000, audio.bitrate)
        val subtitle = probe.tracks.filterIsInstance<SubtitleTrackInfo>().single()
        assertEquals("pol", subtitle.language)
        assertFalse(subtitle.isImageBased)
    }

    @Test
    fun recognizesLegacyAviAndAsfVideoCodecs() {
        val avi = NativeProbeParser.parse(legacyProbe(format = "avi", codec = "mpeg4"))
        val asf = NativeProbeParser.parse(legacyProbe(format = "asf", codec = "wmv2"))

        assertEquals(MediaContainer.AVI, avi.container)
        assertEquals(
            DynamicRangeFormat.SDR,
            avi.tracks
                .filterIsInstance<VideoTrackInfo>()
                .single()
                .colorInfo.dynamicRange,
        )
        assertEquals(
            VideoCodec.MPEG4,
            avi.tracks
                .filterIsInstance<VideoTrackInfo>()
                .single()
                .codec,
        )
        assertEquals(MediaContainer.ASF, asf.container)
        assertEquals(
            VideoCodec.WMV2,
            asf.tracks
                .filterIsInstance<VideoTrackInfo>()
                .single()
                .codec,
        )
    }

    private fun legacyProbe(
        format: String,
        codec: String,
    ): String =
        """
        {
          "format":"$format","durationUs":1000000,
          "tracks":[{
            "type":"video","id":0,"codec":"$codec","profile":null,"level":null,
            "width":640,"height":360,"bitDepth":8,"dynamicRange":"UNKNOWN",
            "colorRange":null,"colorPrimaries":null,"colorTransfer":null,"colorMatrix":null,
            "frameRateNumerator":30,"frameRateDenominator":1,
            "hasHdr10PlusMetadata":false,"dolbyVision":null,
            "masteringDisplay":null,"contentLightLevel":null,
            "language":null,"title":null,"isDefault":true
          }]
        }
        """.trimIndent()
}
