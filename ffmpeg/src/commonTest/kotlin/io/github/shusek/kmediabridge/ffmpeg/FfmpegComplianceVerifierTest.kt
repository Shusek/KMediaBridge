// SPDX-License-Identifier: LGPL-2.1-or-later

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
        val runtime = compliantRuntime().copy(configureArguments = compliantArguments + "--enable-gpl")

        val failure =
            assertFailsWith<MediaBridgeException> {
                FfmpegComplianceVerifier.requireCompliant(runtime)
            }

        assertEquals(MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME, failure.code)
        assertTrue(failure.message.orEmpty().contains("--enable-gpl"))
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
                    configureArguments = compliantArguments - "--disable-static" + "--enable-static",
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
                            sourceArchiveSha256 = sha256,
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
            configureArguments = compliantArguments,
            ffmpegSourceArchiveUrl = "https://github.com/Shusek/KMediaBridge/releases/download/v0.1.0/ffmpeg-8.1.2.tar.xz",
            ffmpegSourceArchiveSha256 = sha256,
            nativeArtifactSha256 = sha256,
            buildRecipeUrl = "https://github.com/Shusek/KMediaBridge/tree/0123456789abcdef/native",
            buildRecipeRevision = "0123456789abcdef",
            exactCorrespondingSourceAvailable = true,
            dynamicLinkingVerified = true,
        )

    private companion object {
        val compliantArguments: List<String> =
            listOf(
                "--disable-gpl",
                "--disable-nonfree",
                "--enable-shared",
                "--disable-static",
            )
        const val sha256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
