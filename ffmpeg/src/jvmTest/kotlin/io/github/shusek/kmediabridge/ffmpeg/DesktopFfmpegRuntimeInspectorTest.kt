// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFfmpegRuntimeInspectorTest {
    @Test
    fun validatesCapabilitiesWithoutRequestingNativeLibraries() {
        val resourceRoot = Files.createTempDirectory("kmediabridge-inspector-test-")
        val platform = currentPlatformId()
        val payload = resourceRoot.resolve("META-INF/kmediabridge/native/$platform")
        Files.createDirectories(payload)
        Files.writeString(payload.resolve("manifest.properties"), validManifest(platform))
        val classLoader = NativeRequestTrackingClassLoader(arrayOf(resourceRoot.toUri().toURL()))

        classLoader.use {
            val status = DesktopFfmpegRuntimeInspector.inspect(classLoader = classLoader)

            assertTrue(status.isDeclaredAvailable)
            assertEquals(FfmpegRuntimeInspectionLevel.MANIFEST_VALIDATED, status.inspectionLevel)
            assertEquals(FfmpegRuntimeFlavor.REMUX_ONLY, status.flavor)
            assertTrue(status.capabilities?.canCopyVideo == true)
            assertFalse(status.capabilities.canBurnSubtitles)
            assertEquals(0, classLoader.nativeLibraryRequests)
        }
        resourceRoot.toFile().deleteRecursively()
    }

    @Test
    fun rejectsAnIncompleteCapabilityDeclarationWithoutLoadingNativeCode() {
        val resourceRoot = Files.createTempDirectory("kmediabridge-inspector-invalid-")
        val platform = currentPlatformId()
        val payload = resourceRoot.resolve("META-INF/kmediabridge/native/$platform")
        Files.createDirectories(payload)
        Files.writeString(
            payload.resolve("manifest.properties"),
            validManifest(platform).replace("capability.canBurnSubtitles=false\n", ""),
        )
        val classLoader = NativeRequestTrackingClassLoader(arrayOf(resourceRoot.toUri().toURL()))

        classLoader.use {
            val status = DesktopFfmpegRuntimeInspector.inspect(classLoader = classLoader)

            assertFalse(status.isDeclaredAvailable)
            assertTrue(status.detail.contains("capability.canBurnSubtitles"))
            assertEquals(0, classLoader.nativeLibraryRequests)
        }
        resourceRoot.toFile().deleteRecursively()
    }

    private fun currentPlatformId(): String {
        val osName = System.getProperty("os.name").lowercase()
        val architecture = System.getProperty("os.arch").lowercase()
        val os =
            when {
                "mac" in osName || "darwin" in osName -> "macos"
                "win" in osName -> "windows"
                else -> "linux"
            }
        val arch = if (architecture in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
        return "$os-$arch"
    }

    private fun validManifest(platform: String): String =
        """
        schemaVersion=1
        platform=$platform
        abiVersion=4
        sharedRuntimeId=kmediaffmpeg-8.1.2-ass-0.17.5-78fbb23ab073fc90
        ffmpegVersion=8.1.2
        ffmpegLicenseSpdx=LGPL-2.1-or-later
        ffmpegReportedLicense=LGPL version 2.1 or later
        sourceOfferUrl=https://example.invalid/ffmpeg.tar.xz
        sourceSha256=${"0".repeat(64)}
        buildRecipeUrl=https://example.invalid/native
        buildRecipeRevision=test
        exactCorrespondingSourceAvailable=true
        dynamicLinkingVerified=true
        runtimeFlavor=REMUX_ONLY
        capability.inputContainers=MATROSKA,WEBM,MP4,FRAGMENTED_MP4,MPEG_TS
        capability.outputs=CMAF_FRAGMENT_STREAM
        capability.canProbe=true
        capability.canCopyVideo=true
        capability.canToneMapToSdr=false
        capability.canConvertDolbyVisionProfile7=false
        capability.supportsLiveInput=false
        capability.supportsEncryptedInput=false
        capability.supportsRemoteInput=false
        capability.canTranscodeVideo=false
        capability.canTranscodeAudio=false
        capability.canBurnSubtitles=false
        component.count=0
        library.count=1
        library.0.name=libkmediabridge.test
        library.0.sha256=${"0".repeat(64)}
        library.0.role=BRIDGE
        """.trimIndent() + "\n"

    private class NativeRequestTrackingClassLoader(
        urls: Array<URL>,
    ) : URLClassLoader(urls, null) {
        var nativeLibraryRequests: Int = 0
            private set

        override fun getResource(name: String): URL? {
            if (!name.endsWith("manifest.properties")) {
                nativeLibraryRequests++
            }
            return super.getResource(name)
        }
    }
}
