// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

public data class NativeComponentInfo(
    public val name: String,
    public val version: String,
    public val licenseSpdx: String,
    public val sourceArchiveUrl: String,
    public val sourceArchiveSha256: String,
)

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
)

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
