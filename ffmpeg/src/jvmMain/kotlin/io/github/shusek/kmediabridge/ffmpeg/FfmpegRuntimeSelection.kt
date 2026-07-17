// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import java.nio.file.Path

/**
 * Selects between the runtime shipped as a separate KMediaBridge artifact and a
 * compatible runtime installed outside the application.
 *
 * An external directory is not an FFmpeg executable or an arbitrary directory
 * of distribution libraries. It must contain a compatible KMediaBridge native
 * bridge, its FFmpeg libraries, and a verifiable `manifest.properties`.
 *
 * KMediaBridge does not convey an external payload, so it may report an
 * effective license other than LGPL, including GPL. Its licensing and any
 * obligations created by linking it into an application are managed by the
 * caller. Technical ABI, identity, path, and hash checks are never disabled.
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
