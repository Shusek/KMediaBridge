// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import java.nio.file.Path

/**
 * Selects between the runtime shipped as a separate KMediaBridge artifact and a
 * compatible runtime installed outside the application.
 *
 * An external directory is not an FFmpeg executable or an arbitrary directory
 * of distribution libraries. It must contain the KMediaBridge native bridge,
 * its dynamically linked FFmpeg libraries, and a verifiable
 * `manifest.properties` produced by the KMediaBridge packaging tools.
 */
public enum class FfmpegRuntimePolicy {
    BUNDLED_ONLY,
    EXTERNAL_ONLY,
    PREFER_BUNDLED,
    PREFER_EXTERNAL,
}

public data class FfmpegRuntimeSelection(
    public val policy: FfmpegRuntimePolicy = FfmpegRuntimePolicy.BUNDLED_ONLY,
    public val externalRuntimeDirectory: Path? = null,
) {
    init {
        val requiresExternalDirectory = policy != FfmpegRuntimePolicy.BUNDLED_ONLY
        require(!requiresExternalDirectory || externalRuntimeDirectory != null) {
            "The selected FFmpeg runtime policy requires an external runtime directory."
        }
        require(policy != FfmpegRuntimePolicy.BUNDLED_ONLY || externalRuntimeDirectory == null) {
            "BUNDLED_ONLY cannot be combined with an external runtime directory."
        }
    }

    public companion object {
        @JvmStatic
        public fun bundled(): FfmpegRuntimeSelection = FfmpegRuntimeSelection()

        @JvmStatic
        public fun fromExternalDirectory(directory: Path): FfmpegRuntimeSelection =
            FfmpegRuntimeSelection(
                policy = FfmpegRuntimePolicy.EXTERNAL_ONLY,
                externalRuntimeDirectory = directory,
            )
    }
}
