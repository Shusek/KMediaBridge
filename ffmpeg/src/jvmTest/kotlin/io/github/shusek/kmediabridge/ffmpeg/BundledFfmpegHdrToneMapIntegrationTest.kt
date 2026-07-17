// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledFfmpegHdrToneMapIntegrationTest {
    @Test
    fun toneMapsConfiguredHdrFixtureToTaggedBt709Cmaf() =
        runBlocking {
            val configuredPath = System.getProperty(TEST_MEDIA_PROPERTY)?.takeIf(String::isNotBlank) ?: return@runBlocking
            val input = Path.of(configuredPath)
            require(Files.isRegularFile(input)) { "The configured HDR integration-test media does not exist." }

            val driver = BundledFfmpegNativeDriver.loadDefault()
            assertTrue(driver.capabilities.canToneMapToSdr)
            val session =
                BundledFfmpegHlsPlaybackBackend.start(
                    request =
                        FfmpegHlsPlaybackRequest(
                            input = MediaInput(input.toString(), MediaInputKind.FILE),
                            videoOutputPolicy = FfmpegHlsVideoOutputPolicy.FORCE_SDR,
                            fragmentDurationUs = 500_000L,
                        ),
                    driver = driver,
                )
            try {
                val output = session.source.outputInfo
                assertEquals(VideoHandling.TONE_MAP_TO_SDR, output.videoHandling)
                assertTrue(
                    output.inputColorInfo?.dynamicRange in
                        setOf(DynamicRangeFormat.HDR10, DynamicRangeFormat.HDR10_PLUS, DynamicRangeFormat.HLG),
                )
                assertBt709(output.outputColorInfo)

                val playlistUri = URI.create(session.source.playlistUrl)
                val playlist = playlistUri.toURL().readText()
                val mediaPath = playlist.lineSequence().firstOrNull { it.startsWith("segment-") }
                assertNotNull(mediaPath)
                val rendered = Files.createTempFile("kmediabridge-tone-map-output-", ".mp4")
                try {
                    Files.newOutputStream(rendered).use { destination ->
                        destination.write(playlistUri.resolve("init.mp4").toURL().readBytes())
                        destination.write(playlistUri.resolve(mediaPath).toURL().readBytes())
                    }
                    val renderedVideo =
                        driver
                            .probe(MediaInput(rendered.toString(), MediaInputKind.FILE))
                            .tracks
                            .filterIsInstance<VideoTrackInfo>()
                            .single()
                    assertBt709(renderedVideo.colorInfo)
                } finally {
                    rendered.deleteIfExists()
                }
            } finally {
                session.closeAsync()
            }
        }

    private fun assertBt709(color: io.github.shusek.kmediabridge.VideoColorInfo?) {
        assertNotNull(color)
        assertEquals(DynamicRangeFormat.SDR, color.dynamicRange)
        assertEquals(8, color.bitDepth)
        assertEquals(ColorRange.LIMITED, color.range)
        assertEquals(ColorPrimaries.BT709, color.primaries)
        assertEquals(ColorTransfer.BT709, color.transfer)
        assertEquals(ColorMatrix.BT709, color.matrix)
    }

    private companion object {
        const val TEST_MEDIA_PROPERTY: String = "kmediabridge.testMedia"
    }
}
