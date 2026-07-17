// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmpegComplianceVerifierTest {
    @Test
    fun acceptsFailClosedLgplRuntimeWithCorrespondingSource() {
        val report = FfmpegComplianceVerifier.verify(compliantRuntime())

        assertTrue(report.isCompliant, report.violations.joinToString())
    }

    @Test
    fun rejectsEnableGplEvenWhenManifestClaimsLgpl() {
        val runtime = compliantRuntime().copy(configureArguments = COMPLIANT_ARGUMENTS + "--enable-gpl")

        val failure =
            assertFailsWith<MediaBridgeException> {
                FfmpegComplianceVerifier.requireCompliant(runtime)
            }

        assertEquals(MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME, failure.code)
        assertTrue(failure.message.orEmpty().contains("--enable-gpl"))
    }

    @Test
    fun allowsCallerProvidedGplRuntimeWithoutCertifyingItAsLgplRedistributable() {
        val runtime =
            compliantRuntime().copy(
                ffmpegLicenseSpdx = "GPL-2.0-or-later",
                ffmpegReportedLicense = "GPL version 2 or later",
                configureArguments =
                    COMPLIANT_ARGUMENTS
                        .filterNot { it == "--disable-gpl" }
                        .plus("--enable-gpl"),
                exactCorrespondingSourceAvailable = false,
                origin = FfmpegRuntimeOrigin.EXTERNAL_DIRECTORY,
            )

        val report = FfmpegComplianceVerifier.verify(runtime)
        assertTrue(report.violations.any { it.code == "FFMPEG_LICENSE" })
        assertEquals(FfmpegRuntimeComplianceScope.CALLER_PROVIDED, runtime.complianceScope)

        FfmpegComplianceVerifier.requireAllowedByDistributionPolicy(runtime)
    }

    @Test
    fun stillRejectsTheSameGplRuntimeWhenKMediaBridgeWouldDistributeIt() {
        val runtime =
            compliantRuntime().copy(
                ffmpegLicenseSpdx = "GPL-2.0-or-later",
                ffmpegReportedLicense = "GPL version 2 or later",
                configureArguments = COMPLIANT_ARGUMENTS + "--enable-gpl",
            )

        assertFailsWith<MediaBridgeException> {
            FfmpegComplianceVerifier.requireAllowedByDistributionPolicy(runtime)
        }
    }

    @Test
    fun rejectsMissingCorrespondingSource() {
        val report =
            FfmpegComplianceVerifier.verify(
                compliantRuntime().copy(exactCorrespondingSourceAvailable = false),
            )

        assertTrue(report.violations.any { it.code == "CORRESPONDING_SOURCE" })
    }

    @Test
    fun rejectsStaticFfmpegBoundary() {
        val report =
            FfmpegComplianceVerifier.verify(
                compliantRuntime().copy(
                    configureArguments = COMPLIANT_ARGUMENTS - "--disable-static" + "--enable-static",
                ),
            )

        assertTrue(report.violations.any { it.code == "DYNAMIC_LINK_BOUNDARY" })
    }

    @Test
    fun rejectsRuntimeWithoutDynamicLinkInspection() {
        val report =
            FfmpegComplianceVerifier.verify(
                compliantRuntime().copy(dynamicLinkingVerified = false),
            )

        assertTrue(report.violations.any { it.code == "DYNAMIC_LINK_INSPECTION" })
    }

    @Test
    fun apacheComponentRequiresLgplVersionThreeCombination() {
        val runtime =
            compliantRuntime().copy(
                linkedComponents =
                    listOf(
                        NativeComponentInfo(
                            name = "sample",
                            version = "1.0",
                            licenseSpdx = "Apache-2.0",
                            sourceArchiveUrl = "https://example.invalid/sample-1.0.tar.xz",
                            sourceArchiveSha256 = SHA256,
                        ),
                    ),
            )

        val report = FfmpegComplianceVerifier.verify(runtime)

        assertTrue(report.violations.any { it.code == "APACHE_REQUIRES_LGPL3" })
    }

    private fun compliantRuntime(): FfmpegRuntimeInfo =
        FfmpegRuntimeInfo(
            ffmpegVersion = "8.1.2",
            ffmpegLicenseSpdx = "LGPL-2.1-or-later",
            ffmpegReportedLicense = "LGPL version 2.1 or later",
            configureArguments = COMPLIANT_ARGUMENTS,
            ffmpegSourceArchiveUrl = "https://github.com/Shusek/KMediaBridge/releases/download/v0.2.0/ffmpeg-8.1.2.tar.xz",
            ffmpegSourceArchiveSha256 = SHA256,
            nativeArtifactSha256 = SHA256,
            buildRecipeUrl = "https://github.com/Shusek/KMediaBridge/tree/0123456789abcdef/native",
            buildRecipeRevision = "0123456789abcdef",
            exactCorrespondingSourceAvailable = true,
            dynamicLinkingVerified = true,
        )

    private companion object {
        val COMPLIANT_ARGUMENTS: List<String> =
            listOf(
                "--disable-gpl",
                "--disable-nonfree",
                "--enable-shared",
                "--disable-static",
            )
        const val SHA256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
