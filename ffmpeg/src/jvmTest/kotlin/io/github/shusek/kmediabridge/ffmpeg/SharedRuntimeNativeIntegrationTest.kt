// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediaffmpeg.runtime.KMediaFfmpegRuntime
import io.github.shusek.kmediaffmpeg.runtime.RuntimeSource
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedRuntimeNativeIntegrationTest {
    @Test
    fun loadsOnlyTheBridgeClientAfterTheSharedRuntime() {
        val runtimeSdk = System.getProperty("kmediabridge.testRuntimeSdk") ?: return
        val clientOutput = System.getProperty("kmediabridge.testClientOutput") ?: return
        val report =
            KMediaFfmpegRuntime.initialize(
                RuntimeSource.externalDirectory(Path.of(runtimeSdk).toFile()),
            )
        val osName = System.getProperty("os.name", "").lowercase()
        val (platformId, libraryName) =
            when {
                "win" in osName -> "windows-x86_64" to "kmediabridge.dll"
                "mac" in osName || "darwin" in osName -> "macos-aarch64" to "libkmediabridge.dylib"
                else -> error("The shared-runtime integration test does not support this host.")
            }
        val resources = Files.createTempDirectory("kmediabridge-shared-runtime-test-")
        val platform = resources.resolve("META-INF/kmediabridge/native/$platformId")
        Files.createDirectories(platform)
        Files.copy(Path.of(clientOutput, "manifest.properties"), platform.resolve("manifest.properties"))
        Files.copy(
            Path.of(clientOutput, "runtime", libraryName),
            platform.resolve(libraryName),
        )
        URLClassLoader(arrayOf(resources.toUri().toURL()), javaClass.classLoader).use { loader ->
            val driver = BundledFfmpegNativeDriver.load(classLoader = loader)
            assertEquals(report.runtimeId(), driver.runtimeInfo.sharedRuntimeId)
            assertEquals(report.configurationSha256(), driver.runtimeInfo.sharedRuntimeConfigurationSha256)
            assertEquals("9.0.1", driver.runtimeInfo.ffmpegVersion)
            assertTrue(driver.runtimeInfo.configureArguments.contains("--disable-gpl"))
        }
        resources.toFile().deleteRecursively()
    }
}
