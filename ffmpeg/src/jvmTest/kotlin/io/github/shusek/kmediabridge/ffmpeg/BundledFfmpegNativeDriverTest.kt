// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.SubtitleTrackInfo
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.Comparator
import java.util.Properties
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
                assertEquals(FfmpegRuntimeOrigin.BUNDLED, driver.runtimeInfo.origin)
                assertEquals(
                    FfmpegRuntimeComplianceScope.KMEDIABRIDGE_DISTRIBUTED,
                    driver.runtimeInfo.complianceScope,
                )
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

    @Test
    fun burnsSelectedTextSubtitleIntoSdrCmafWhenFullRuntimeIsPresent() =
        runTest {
            val platform = hostPlatformId() ?: return@runTest
            val loader = BundledFfmpegNativeDriverTest::class.java.classLoader
            if (loader.getResource("META-INF/kmediabridge/native/$platform/manifest.properties") == null) {
                return@runTest
            }

            val driver = BundledFfmpegNativeDriver.load(classLoader = loader)
            if (!driver.capabilities.canBurnSubtitles) return@runTest

            val input = Files.createTempFile("kmediabridge-subtitle-test-", ".mkv")
            try {
                val encoded =
                    loader
                        .getResourceAsStream("kmediabridge-subtitle-test.mkv.b64")!!
                        .bufferedReader()
                        .readText()
                Files.write(input, Base64.getMimeDecoder().decode(encoded))
                val mediaInput = MediaInput(input.toString(), MediaInputKind.FILE)
                val subtitle =
                    driver
                        .probe(mediaInput)
                        .tracks
                        .filterIsInstance<SubtitleTrackInfo>()
                        .single()
                val session =
                    driver.open(
                        mediaInput,
                        BridgeRequest(
                            videoHandling = VideoHandling.TRANSCODE_TO_SDR,
                            subtitleHandling = SubtitleHandling.BURN_IN,
                            preferredSubtitleTrackId = subtitle.id,
                            fragmentDurationUs = 500_000L,
                        ),
                    )
                val events = mutableListOf<MediaBridgeEvent>()
                session.events.onEach(events::add).first { it is MediaBridgeEvent.EndOfStream }
                session.close()

                val output = events.filterIsInstance<MediaBridgeEvent.OutputConfigured>().single().value
                assertEquals(VideoHandling.TRANSCODE_TO_SDR, output.videoHandling)
                assertEquals(SubtitleHandling.BURN_IN, output.subtitleHandling)
                assertEquals(subtitle.id, output.selectedSubtitleTrackId)
                assertEquals(8, output.outputColorInfo?.bitDepth)
                val fragments = events.filterIsInstance<MediaBridgeEvent.Fragment>().map { it.value }
                assertTrue(fragments.first().isInitialization)
                assertTrue(fragments.drop(1).any { !it.isInitialization && it.bytes.isNotEmpty() })

                val rendered = Files.createTempFile("kmediabridge-subtitle-output-", ".mp4")
                try {
                    Files.newOutputStream(rendered).use { destination ->
                        fragments.forEach { destination.write(it.bytes) }
                    }
                    val renderedProbe = driver.probe(MediaInput(rendered.toString(), MediaInputKind.FILE))
                    val renderedVideo = renderedProbe.tracks.filterIsInstance<VideoTrackInfo>().single()
                    assertEquals(DynamicRangeFormat.SDR, renderedVideo.colorInfo.dynamicRange)
                    assertEquals(8, renderedVideo.colorInfo.bitDepth)
                    assertEquals(ColorRange.LIMITED, renderedVideo.colorInfo.range)
                    assertEquals(ColorPrimaries.BT709, renderedVideo.colorInfo.primaries)
                    assertEquals(ColorTransfer.BT709, renderedVideo.colorInfo.transfer)
                    assertEquals(ColorMatrix.BT709, renderedVideo.colorInfo.matrix)
                    assertTrue(renderedProbe.tracks.none { it is SubtitleTrackInfo })
                } finally {
                    rendered.deleteIfExists()
                }
            } finally {
                input.deleteIfExists()
            }
        }

    @Test
    fun loadsCallerProvidedRuntimeWithoutProjectDistributionEvidence() =
        runTest {
            val platform = hostPlatformId() ?: return@runTest
            val loader = BundledFfmpegNativeDriverTest::class.java.classLoader
            val resourcePrefix = "META-INF/kmediabridge/native/$platform"
            if (loader.getResource("$resourcePrefix/manifest.properties") == null) {
                return@runTest
            }

            val externalDirectory = Files.createTempDirectory("kmediabridge-external-test-")
            try {
                copyPackagedRuntime(loader, resourcePrefix, externalDirectory)
                removeProjectDistributionEvidence(externalDirectory.resolve("manifest.properties"))
                val driver =
                    BundledFfmpegNativeDriver.load(
                        runtimeSelection = FfmpegRuntimeSelection.fromExternalDirectory(externalDirectory),
                        classLoader = loader,
                    )

                assertEquals(FfmpegRuntimeOrigin.EXTERNAL_DIRECTORY, driver.runtimeInfo.origin)
                assertEquals(FfmpegRuntimeComplianceScope.CALLER_PROVIDED, driver.runtimeInfo.complianceScope)
                assertEquals("8.1.2", driver.runtimeInfo.ffmpegVersion)
                assertEquals("", driver.runtimeInfo.ffmpegSourceArchiveUrl)
                assertEquals(false, driver.runtimeInfo.exactCorrespondingSourceAvailable)
            } finally {
                Files.walk(externalDirectory).use { paths ->
                    paths
                        .sorted(Comparator.reverseOrder())
                        .forEach(Files::deleteIfExists)
                }
            }
        }

    private fun removeProjectDistributionEvidence(manifest: java.nio.file.Path) {
        val properties =
            Properties().apply {
                Files.newInputStream(manifest).use(::load)
            }
        listOf(
            "sourceOfferUrl",
            "sourceSha256",
            "buildRecipeUrl",
            "buildRecipeRevision",
            "exactCorrespondingSourceAvailable",
            "dynamicLinkingVerified",
        ).forEach(properties::remove)
        Files.newOutputStream(manifest).use { output -> properties.store(output, null) }
    }

    private fun copyPackagedRuntime(
        loader: ClassLoader,
        resourcePrefix: String,
        destination: java.nio.file.Path,
    ) {
        val manifestName = "manifest.properties"
        val properties =
            Properties().apply {
                loader.getResourceAsStream("$resourcePrefix/$manifestName")!!.use(::load)
            }
        loader.getResourceAsStream("$resourcePrefix/$manifestName")!!.use { input ->
            Files.copy(input, destination.resolve(manifestName))
        }
        repeat(properties.getProperty("library.count").toInt()) { index ->
            val name = properties.getProperty("library.$index.name")
            loader.getResourceAsStream("$resourcePrefix/$name")!!.use { input ->
                Files.copy(input, destination.resolve(name))
            }
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
