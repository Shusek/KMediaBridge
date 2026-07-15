// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

public data class NativeComponentInfo(
    public val name: String,
    public val version: String,
    public val licenseSpdx: String,
    public val sourceArchiveUrl: String,
    public val sourceArchiveSha256: String,
)

/** Identifies where the verified native FFmpeg runtime was loaded from. */
public enum class FfmpegRuntimeOrigin {
    /** Native payload conveyed by the KMediaBridge runtime artifact. */
    BUNDLED,

    /** Native payload explicitly supplied by the application or its operator. */
    EXTERNAL_DIRECTORY,
}

/** Identifies who is responsible for the selected native payload's distribution compliance. */
public enum class FfmpegRuntimeComplianceScope {
    KMEDIABRIDGE_DISTRIBUTED,
    CALLER_PROVIDED,
}

public data class FfmpegRuntimeInfo(
    public val ffmpegVersion: String,
    public val ffmpegLicenseSpdx: String,
    public val ffmpegReportedLicense: String,
    public val configureArguments: List<String>,
    public val ffmpegSourceArchiveUrl: String,
    public val ffmpegSourceArchiveSha256: String,
    public val nativeArtifactSha256: String,
    public val buildRecipeUrl: String,
    public val buildRecipeRevision: String,
    public val exactCorrespondingSourceAvailable: Boolean,
    public val dynamicLinkingVerified: Boolean,
    public val linkedComponents: List<NativeComponentInfo> = emptyList(),
    public val origin: FfmpegRuntimeOrigin = FfmpegRuntimeOrigin.BUNDLED,
) {
    /**
     * Bundled payloads are covered by KMediaBridge's fail-closed LGPL release
     * policy. External payloads may use another effective license, including
     * GPL, and remain the caller's distribution responsibility.
     */
    public val complianceScope: FfmpegRuntimeComplianceScope
        get() =
            when (origin) {
                FfmpegRuntimeOrigin.BUNDLED -> FfmpegRuntimeComplianceScope.KMEDIABRIDGE_DISTRIBUTED
                FfmpegRuntimeOrigin.EXTERNAL_DIRECTORY -> FfmpegRuntimeComplianceScope.CALLER_PROVIDED
            }
}

public data class ComplianceViolation(
    public val code: String,
    public val message: String,
)

public data class FfmpegComplianceReport(
    public val violations: List<ComplianceViolation>,
) {
    public val isCompliant: Boolean
        get() = violations.isEmpty()
}
