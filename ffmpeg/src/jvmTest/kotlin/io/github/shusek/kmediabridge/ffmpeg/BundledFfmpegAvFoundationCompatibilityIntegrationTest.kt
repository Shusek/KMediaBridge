// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.AudioTrackInfo
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.VideoCodec
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledFfmpegAvFoundationCompatibilityIntegrationTest {
    @Test
    fun transcodesConfiguredLegacyFixtureToAvcAacCmaf() =
        runBlocking {
            val configuredPath =
                System.getProperty(TEST_MEDIA_PROPERTY)?.takeIf(String::isNotBlank) ?: return@runBlocking
            val driver =
                System
                    .getProperty(EXTERNAL_RUNTIME_PROPERTY)
                    ?.takeIf(String::isNotBlank)
                    ?.let { runtimeDirectory ->
                        BundledFfmpegNativeDriver.load(
                            runtimeSelection =
                                FfmpegRuntimeSelection.fromExternalDirectory(Path.of(runtimeDirectory)),
                        )
                    } ?: BundledFfmpegNativeDriver.loadDefault()
            assertTrue(driver.capabilities.canTranscodeVideo)
            assertTrue(driver.capabilities.canTranscodeAudio)
            val configured = Path.of(configuredPath)
            val inputs =
                if (Files.isDirectory(configured)) {
                    Files.list(configured).use { paths ->
                        paths
                            .filter(Files::isRegularFile)
                            .filter { path ->
                                path.fileName
                                    .toString()
                                    .substringAfterLast('.', "")
                                    .lowercase() in LEGACY_EXTENSIONS
                            }.sorted()
                            .toList()
                    }
                } else {
                    listOf(configured)
                }
            require(inputs.isNotEmpty() && inputs.all(Files::isRegularFile)) {
                "The configured legacy integration-test media does not exist."
            }
            inputs.forEach { input -> transcodeAndVerify(driver, input) }
        }

    private suspend fun transcodeAndVerify(
        driver: BundledFfmpegNativeDriver,
        input: Path,
    ) {
        val original = driver.probe(MediaInput(input.toString(), MediaInputKind.FILE))
        assertTrue(original.container in setOf(MediaContainer.AVI, MediaContainer.ASF))
        val originalVideo = original.tracks.filterIsInstance<VideoTrackInfo>().single()

        val session =
            BundledFfmpegHlsPlaybackBackend.start(
                request =
                    FfmpegHlsPlaybackRequest(
                        input = MediaInput(input.toString(), MediaInputKind.FILE),
                        videoOutputPolicy = FfmpegHlsVideoOutputPolicy.AVFOUNDATION_COMPATIBLE_SDR,
                        fragmentDurationUs = 500_000L,
                    ),
                driver = driver,
            )
        try {
            assertEquals(VideoHandling.TRANSCODE_TO_SDR, session.source.outputInfo.videoHandling)
            assertEquals(AudioHandling.TRANSCODE_AAC, session.source.outputInfo.audioHandling)
            val playlistUri = URI.create(session.source.playlistUrl)
            val playlist =
                withTimeout(30_000L) {
                    while (true) {
                        val candidate = playlistUri.toURL().readText()
                        if ("#EXT-X-ENDLIST" in candidate) return@withTimeout candidate
                        delay(25L)
                    }
                    error("unreachable")
                }
            val mediaPaths = playlist.lineSequence().filter { it.startsWith("segment-") }.toList()
            assertTrue(mediaPaths.isNotEmpty())
            val rendered = Files.createTempFile("kmediabridge-avfoundation-output-", ".mp4")
            try {
                Files.newOutputStream(rendered).use { destination ->
                    destination.write(playlistUri.resolve("init.mp4").toURL().readBytes())
                    mediaPaths.forEach { mediaPath ->
                        destination.write(playlistUri.resolve(mediaPath).toURL().readBytes())
                    }
                }
                val renderedProbe = driver.probe(MediaInput(rendered.toString(), MediaInputKind.FILE))
                val renderedVideo = renderedProbe.tracks.filterIsInstance<VideoTrackInfo>().single()
                assertEquals(
                    VideoCodec.AVC,
                    renderedVideo.codec,
                )
                assertEquals(originalVideo.width, renderedVideo.width)
                assertEquals(originalVideo.height, renderedVideo.height)
                assertEquals(
                    checkNotNull(originalVideo.frameRate),
                    checkNotNull(renderedVideo.frameRate),
                    absoluteTolerance = 1.0,
                )
                assertEquals(
                    "aac",
                    renderedProbe.tracks
                        .filterIsInstance<AudioTrackInfo>()
                        .single()
                        .codecName,
                )
            } finally {
                rendered.deleteIfExists()
            }
        } finally {
            session.closeAsync()
        }
    }

    private companion object {
        const val TEST_MEDIA_PROPERTY: String = "kmediabridge.legacyTestMedia"
        const val EXTERNAL_RUNTIME_PROPERTY: String = "kmediabridge.externalRuntimeDirectory"
        val LEGACY_EXTENSIONS: Set<String> = setOf("avi", "wmv", "asf")
    }
}
