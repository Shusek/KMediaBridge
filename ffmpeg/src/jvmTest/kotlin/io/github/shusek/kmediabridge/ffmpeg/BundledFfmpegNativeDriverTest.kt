// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledFfmpegNativeDriverTest {
    @Test
    fun loadsProbesAndStreamsWhenHostPayloadIsPresent() =
        runTest {
            val platform = hostPlatformId() ?: return@runTest
            val loader = BundledFfmpegNativeDriverTest::class.java.classLoader
            if (loader.getResource("META-INF/kmediabridge/native/$platform/manifest.properties") == null) {
                return@runTest
            }

            val input = Files.createTempFile("kmediabridge-test-", ".mkv")
            try {
                val encoded = loader.getResourceAsStream("kmediabridge-test.mkv.b64")!!.bufferedReader().readText()
                Files.write(input, Base64.getMimeDecoder().decode(encoded))
                val driver = BundledFfmpegNativeDriver.load(classLoader = loader)
                assertEquals("8.1.2", driver.runtimeInfo.ffmpegVersion)
                assertTrue(driver.runtimeInfo.dynamicLinkingVerified)

                val mediaInput = MediaInput(input.toString(), MediaInputKind.FILE)
                val probe = driver.probe(mediaInput)
                assertTrue(probe.tracks.any { it is VideoTrackInfo })

                val session = driver.open(mediaInput, BridgeRequest(fragmentDurationUs = 500_000L))
                val events = mutableListOf<MediaBridgeEvent>()
                session.events.onEach(events::add).first { it is MediaBridgeEvent.EndOfStream }
                session.close()

                val configured = events.filterIsInstance<MediaBridgeEvent.OutputConfigured>().single().value
                assertEquals(VideoHandling.COPY, configured.videoHandling)
                val fragments = events.filterIsInstance<MediaBridgeEvent.Fragment>().map { it.value }
                assertTrue(fragments.first().isInitialization)
                assertTrue(fragments.drop(1).any { !it.isInitialization && it.bytes.isNotEmpty() })
            } finally {
                input.deleteIfExists()
            }
        }

    @Test
    fun servesRealCmafFromThePackagedRuntime() =
        runBlocking {
            val platform = hostPlatformId() ?: return@runBlocking
            val loader = BundledFfmpegNativeDriverTest::class.java.classLoader
            if (loader.getResource("META-INF/kmediabridge/native/$platform/manifest.properties") == null) {
                return@runBlocking
            }

            val input = Files.createTempFile("kmediabridge-hls-test-", ".mkv")
            try {
                val encoded = loader.getResourceAsStream("kmediabridge-test.mkv.b64")!!.bufferedReader().readText()
                Files.write(input, Base64.getMimeDecoder().decode(encoded))
                val session =
                    BundledFfmpegHlsPlaybackBackend.start(
                        request =
                            FfmpegHlsPlaybackRequest(
                                input = MediaInput(input.toString(), MediaInputKind.FILE),
                                fragmentDurationUs = 500_000L,
                            ),
                        driver = BundledFfmpegNativeDriver.load(classLoader = loader),
                    )
                try {
                    val playlistUri = URI.create(session.source.playlistUrl)
                    val playlist = playlistUri.toURL().readText()
                    assertTrue("#EXT-X-MAP:URI=\"init.mp4\"" in playlist)
                    val mediaPath = playlist.lineSequence().firstOrNull { it.startsWith("segment-") }
                    assertNotNull(mediaPath)
                    assertTrue(
                        playlistUri
                            .resolve("init.mp4")
                            .toURL()
                            .readBytes()
                            .isNotEmpty(),
                    )
                    assertTrue(
                        playlistUri
                            .resolve(mediaPath)
                            .toURL()
                            .readBytes()
                            .isNotEmpty(),
                    )
                } finally {
                    session.closeAsync()
                }
            } finally {
                input.deleteIfExists()
            }
        }

    private fun hostPlatformId(): String? {
        val os = System.getProperty("os.name", "").lowercase()
        val arch = System.getProperty("os.arch", "").lowercase()
        val osPart =
            when {
                "mac" in os || "darwin" in os -> "macos"
                "win" in os -> "windows"
                "linux" in os -> "linux"
                else -> return null
            }
        val archPart =
            when (arch) {
                "aarch64", "arm64" -> "aarch64"
                "amd64", "x86_64", "x64" -> "x86_64"
                else -> return null
            }
        return "$osPart-$archPart"
    }
}
