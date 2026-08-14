// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediaffmpeg.runtime.RuntimeSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FfmpegRuntimeSelectionTest {
    @Test
    fun externalPoliciesRequireAnExplicitDirectory() {
        assertFailsWith<IllegalArgumentException> {
            FfmpegRuntimeSelection(FfmpegRuntimePolicy.EXTERNAL_ONLY)
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegRuntimeSelection(FfmpegRuntimePolicy.PREFER_EXTERNAL)
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegRuntimeSelection(FfmpegRuntimePolicy.PREFER_BUNDLED)
        }
    }

    @Test
    fun preferenceFallbackDependsOnlyOnManifestAvailability() {
        val externalDirectory = Path.of("definitely-missing-kmediabridge-runtime")
        val classLoaderWithoutBundledRuntime = object : ClassLoader(null) {}

        assertEquals(
            externalDirectory,
            DesktopRuntimeLoader.selectExternalRuntimeDirectory(
                FfmpegRuntimeSelection(
                    policy = FfmpegRuntimePolicy.PREFER_BUNDLED,
                    externalRuntimeDirectory = externalDirectory,
                ),
                classLoaderWithoutBundledRuntime,
            ),
        )
        assertNull(
            DesktopRuntimeLoader.selectExternalRuntimeDirectory(
                FfmpegRuntimeSelection(
                    policy = FfmpegRuntimePolicy.PREFER_EXTERNAL,
                    externalRuntimeDirectory = externalDirectory,
                ),
                classLoaderWithoutBundledRuntime,
            ),
        )
        assertEquals(
            externalDirectory,
            DesktopRuntimeLoader.selectExternalRuntimeDirectory(
                FfmpegRuntimeSelection.fromExternalDirectory(externalDirectory),
                classLoaderWithoutBundledRuntime,
            ),
        )
    }

    @Test
    fun combinedReplacementSelectsExternalSharedRuntimeOnlyWhenComplete() {
        val directory = Files.createTempDirectory("kmediabridge-runtime-selection-")
        try {
            assertEquals(
                RuntimeSource.bundled(),
                DesktopRuntimeLoader.selectSharedRuntimeSource(directory),
            )

            Files.createFile(directory.resolve("runtime.properties"))
            assertFailsWith<MediaBridgeException> {
                DesktopRuntimeLoader.selectSharedRuntimeSource(directory)
            }

            Files.createFile(directory.resolve("ass-runtime.properties"))
            Files.createDirectory(directory.resolve("lib"))
            assertEquals(
                RuntimeSource.externalDirectory(directory.toFile()),
                DesktopRuntimeLoader.selectSharedRuntimeSource(directory),
            )
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
