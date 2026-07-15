// SPDX-License-Identifier: LGPL-2.1-or-later

import org.gradle.api.tasks.Exec

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ktlint)
}

val publicationVersion = providers.gradleProperty("publicationVersion").orElse("0.2.0-SNAPSHOT")
val pythonExecutable = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"

allprojects {
    group = "io.github.shusek"
    version = publicationVersion.get()
}

ktlint {
    version.set("1.7.1")
}

val complianceOutput = layout.buildDirectory.file("reports/compliance/sbom.cdx.json")
val nativePayloadDirectory = providers.gradleProperty("nativePayloadDirectory")

val generateComplianceSbom =
    tasks.register<Exec>("generateComplianceSbom") {
        group = "compliance"
        description = "Generates the checked-in distribution SBOM from declared components."
        inputs.files(
            layout.projectDirectory.file("compliance/ffmpeg/manifest.json"),
            layout.projectDirectory.file("gradle/libs.versions.toml"),
            layout.projectDirectory.file("scripts/generate_sbom.py"),
        )
        nativePayloadDirectory.orNull?.let { inputs.dir(it) }
        outputs.file(complianceOutput)
        val arguments =
            mutableListOf(
                pythonExecutable,
                "scripts/generate_sbom.py",
                "--output",
                complianceOutput.get().asFile.absolutePath,
                "--version",
                publicationVersion.get(),
            )
        nativePayloadDirectory.orNull?.let { directory ->
            arguments += listOf("--runtime-resources", directory)
        }
        commandLine(arguments)
    }

val verifyCompliance =
    tasks.register<Exec>("verifyCompliance") {
        group = "verification"
        description = "Fails closed when licensing or FFmpeg source-offer evidence is incomplete."
        inputs.files(
            fileTree(layout.projectDirectory) {
                include("api/src/**")
                include("ffmpeg/src/**")
                include("ffmpeg-runtime-desktop/src/**")
                include("native/**")
                include("scripts/**")
                include("compliance/**")
                include("*.gradle.kts")
                include("LICENSE")
                exclude("**/__pycache__/**")
            },
        )
        commandLine(
            pythonExecutable,
            "scripts/verify_compliance.py",
            "--root",
            layout.projectDirectory.asFile.absolutePath,
        )
    }

val verifyPublications =
    tasks.register<Exec>("verifyPublications") {
        group = "verification"
        description = "Inspects publications and rejects undocumented native payloads."
        dependsOn(
            ":api:publishAllPublicationsToComplianceRepository",
            ":ffmpeg:publishAllPublicationsToComplianceRepository",
            ":ffmpeg-runtime-desktop:publishAllPublicationsToComplianceRepository",
        )
        inputs.dir(layout.buildDirectory.dir("compliance-repository"))
        commandLine(
            pythonExecutable,
            "scripts/verify_publications.py",
            "--repository",
            layout.buildDirectory
                .dir("compliance-repository")
                .get()
                .asFile.absolutePath,
        )
    }

tasks.named("check") {
    dependsOn(":api:check", ":ffmpeg:check", ":ffmpeg-runtime-desktop:check")
    dependsOn(verifyCompliance)
    dependsOn(generateComplianceSbom)
}

tasks.register("complianceCheck") {
    group = "verification"
    dependsOn(verifyCompliance)
    dependsOn(verifyPublications)
    dependsOn(generateComplianceSbom)
}
