// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeException

public object FfmpegComplianceVerifier {
    private val acceptedFfmpegLicenses: Set<String> =
        setOf(
            "LGPL-2.1-or-later",
            "LGPL-3.0-or-later",
        )

    private val acceptedComponentLicenses: Set<String> =
        setOf(
            "0BSD",
            "Apache-2.0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "BSL-1.0",
            "FTL",
            "ISC",
            "LGPL-2.1-or-later",
            "LGPL-3.0-or-later",
            "MIT",
            "Unicode-3.0",
            "WTFPL",
            "Zlib",
        )

    private val forbiddenArguments: Set<String> =
        setOf(
            "--enable-gpl",
            "--enable-nonfree",
            "--enable-libx264",
            "--enable-libx265",
            "--enable-libxvid",
            "--enable-libvidstab",
            "--enable-frei0r",
            "--enable-librubberband",
            "--enable-libcdio",
            "--enable-libdavs2",
            "--enable-libxavs",
            "--enable-libxavs2",
            "--enable-smbclient",
        )

    public fun verify(runtime: FfmpegRuntimeInfo): FfmpegComplianceReport {
        val violations = mutableListOf<ComplianceViolation>()
        val configureArguments = runtime.configureArguments.toSet()

        if (runtime.ffmpegLicenseSpdx !in acceptedFfmpegLicenses) {
            violations +=
                violation(
                    "FFMPEG_LICENSE",
                    "The FFmpeg runtime must be declared as LGPL-2.1-or-later or LGPL-3.0-or-later.",
                )
        }
        if (!runtime.ffmpegReportedLicense.contains("LGPL", ignoreCase = true)) {
            violations += violation("FFMPEG_REPORTED_LICENSE", "The runtime did not report an LGPL license.")
        }
        if ("--disable-gpl" !in configureArguments || "--disable-nonfree" !in configureArguments) {
            violations +=
                violation(
                    "FAIL_CLOSED_FLAGS",
                    "The build must explicitly contain --disable-gpl and --disable-nonfree.",
                )
        }
        if ("--disable-static" !in configureArguments || "--enable-shared" !in configureArguments) {
            violations +=
                violation(
                    "DYNAMIC_LINK_BOUNDARY",
                    "The distributed runtime must disable static FFmpeg libraries and enable shared libraries.",
                )
        }

        forbiddenArguments
            .intersect(configureArguments)
            .sorted()
            .forEach { argument ->
                violations += violation("FORBIDDEN_BUILD_FLAG", "Forbidden FFmpeg build argument: $argument")
            }

        if (!runtime.exactCorrespondingSourceAvailable) {
            violations +=
                violation(
                    "CORRESPONDING_SOURCE",
                    "The exact source corresponding to the native binary must be available.",
                )
        }
        if (!runtime.dynamicLinkingVerified) {
            violations +=
                violation(
                    "DYNAMIC_LINK_INSPECTION",
                    "The native artifact must be inspected and confirmed to link FFmpeg dynamically.",
                )
        }
        requireHttps("FFMPEG_SOURCE_URL", runtime.ffmpegSourceArchiveUrl, violations)
        requireSha256("FFMPEG_SOURCE_SHA256", runtime.ffmpegSourceArchiveSha256, violations)
        requireHttps("BUILD_RECIPE_URL", runtime.buildRecipeUrl, violations)
        if (runtime.buildRecipeRevision.isBlank()) {
            violations += violation("BUILD_RECIPE_REVISION", "The immutable build recipe revision is missing.")
        }
        requireSha256("NATIVE_ARTIFACT_SHA256", runtime.nativeArtifactSha256, violations)

        runtime.linkedComponents.forEach { component ->
            if (component.licenseSpdx !in acceptedComponentLicenses) {
                violations +=
                    violation(
                        "COMPONENT_LICENSE",
                        "${component.name} uses an unapproved license expression: ${component.licenseSpdx}",
                    )
            }
            if (component.licenseSpdx == "Apache-2.0" && runtime.ffmpegLicenseSpdx != "LGPL-3.0-or-later") {
                violations +=
                    violation(
                        "APACHE_REQUIRES_LGPL3",
                        "Apache-2.0 components require the FFmpeg combination to use LGPL-3.0-or-later.",
                    )
            }
            requireHttps("COMPONENT_SOURCE_URL", component.sourceArchiveUrl, violations, component.name)
            requireSha256("COMPONENT_SOURCE_SHA256", component.sourceArchiveSha256, violations, component.name)
        }

        return FfmpegComplianceReport(violations)
    }

    public fun requireCompliant(runtime: FfmpegRuntimeInfo) {
        val report = verify(runtime)
        if (!report.isCompliant) {
            throw MediaBridgeException(
                code = MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                message =
                    report.violations.joinToString(
                        prefix = "The native FFmpeg runtime was rejected: ",
                        separator = "; ",
                    ) { "${it.code}: ${it.message}" },
            )
        }
    }

    /**
     * Applies KMediaBridge's LGPL redistribution gate only to a native payload
     * conveyed by KMediaBridge. A caller-provided runtime is still expected to
     * pass the loader's ABI, identity, path, and hash checks, but its effective
     * license and any resulting application obligations belong to the caller.
     * Call [verify] explicitly when an application wants to require the same
     * LGPL-only policy for an external runtime.
     */
    public fun requireAllowedByDistributionPolicy(runtime: FfmpegRuntimeInfo) {
        if (runtime.complianceScope == FfmpegRuntimeComplianceScope.KMEDIABRIDGE_DISTRIBUTED) {
            requireCompliant(runtime)
        }
    }

    private fun requireHttps(
        code: String,
        value: String,
        violations: MutableList<ComplianceViolation>,
        component: String? = null,
    ) {
        if (!value.startsWith("https://") || value.contains('@')) {
            violations +=
                violation(
                    code,
                    "${component?.let { "$it: " }.orEmpty()}source and recipe URLs must use HTTPS without credentials.",
                )
        }
    }

    private fun requireSha256(
        code: String,
        value: String,
        violations: MutableList<ComplianceViolation>,
        component: String? = null,
    ) {
        if (!SHA256.matches(value)) {
            violations +=
                violation(
                    code,
                    "${component?.let { "$it: " }.orEmpty()}a lowercase 64-character SHA-256 is required.",
                )
        }
    }

    private fun violation(
        code: String,
        message: String,
    ): ComplianceViolation = ComplianceViolation(code, message)

    private val SHA256: Regex = Regex("^[0-9a-f]{64}$")
}
