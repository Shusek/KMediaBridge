// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.BridgeCapabilities

/** Feature set of a desktop native payload, declared by its verified manifest. */
public enum class FfmpegRuntimeFlavor {
    /** Probe and stream-copy/remux only; no decoded video frames are produced. */
    REMUX_ONLY,

    /** Includes a decoded SDR video path and libass subtitle composition. */
    SUBTITLE_BURN_IN_SDR,
}

/** How far a non-loading runtime inspection was able to confirm a payload. */
public enum class FfmpegRuntimeInspectionLevel {
    NOT_AVAILABLE,

    /** The manifest is valid; native files are deliberately not loaded by this inspection. */
    MANIFEST_VALIDATED,
}

/**
 * Result of inspecting a desktop runtime without extracting or loading any
 * native library.
 *
 * A manifest-validated runtime is a candidate, not a promise that native
 * loading will succeed. Hashes, ABI, FFmpeg identity, and licensing are checked
 * again by [BundledFfmpegNativeDriver.load] at the moment the backend is used.
 */
public data class DesktopFfmpegRuntimeStatus(
    public val inspectionLevel: FfmpegRuntimeInspectionLevel,
    public val origin: FfmpegRuntimeOrigin?,
    public val ffmpegVersion: String?,
    public val flavor: FfmpegRuntimeFlavor?,
    public val capabilities: BridgeCapabilities?,
    public val detail: String,
) {
    public val isDeclaredAvailable: Boolean
        get() = inspectionLevel == FfmpegRuntimeInspectionLevel.MANIFEST_VALIDATED
}

/** Manifest-only desktop runtime discovery. This API never calls JNA. */
public object DesktopFfmpegRuntimeInspector {
    @JvmStatic
    @JvmOverloads
    public fun inspect(
        runtimeSelection: FfmpegRuntimeSelection = FfmpegRuntimeSelection.bundled(),
        classLoader: ClassLoader =
            Thread.currentThread().contextClassLoader
                ?: DesktopFfmpegRuntimeInspector::class.java.classLoader,
    ): DesktopFfmpegRuntimeStatus =
        DesktopRuntimeLoader.inspect(
            runtimeSelection = runtimeSelection,
            classLoader = classLoader,
        )
}
