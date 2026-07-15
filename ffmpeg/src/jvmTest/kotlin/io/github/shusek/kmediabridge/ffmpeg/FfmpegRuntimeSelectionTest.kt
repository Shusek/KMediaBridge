// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import java.nio.file.Path
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
}
