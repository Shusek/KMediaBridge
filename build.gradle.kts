// SPDX-License-Identifier: LGPL-2.1-or-later

import org.gradle.api.tasks.Exec

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ktlint)
}

val publicationVersion = providers.gradleProperty("publicationVersion").orElse("0.4.2-SNAPSHOT")
val pythonExecutable = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"

allprojects {
    group = "io.github.shusek"
    version = publicationVersion.get()
}

ktlint {
    version.set("1.7.1")
}

val complianceOutput = layout.buildDirectory.file("reports/compliance/sbom.cdx.json")
val internalCoreComplianceRepository = layout.buildDirectory.dir("internal-core-compliance-repository")
val runtimeComplianceRepository = layout.buildDirectory.dir("runtime-compliance-repository")
val desktopNativePayloadDirectory =
    providers
        .gradleProperty("desktopNativePayloadDirectory")
        .orElse(providers.gradleProperty("nativePayloadDirectory"))
val androidNativePayloadDirectory = providers.gradleProperty("androidNativePayloadDirectory")

val cleanComplianceRepositories =
    tasks.register<Delete>("cleanComplianceRepositories") {
        group = "verification"
        description = "Removes stale staged Maven snapshots before compliance publication."
        delete(internalCoreComplianceRepository, runtimeComplianceRepository)
    }

subprojects {
    tasks
        .matching {
            it.name.startsWith("publish") &&
                (
                    it.name.endsWith("PublicationToInternalCoreComplianceRepository") ||
                        it.name.endsWith("PublicationToRuntimeComplianceRepository")
                )
        }.configureEach {
            dependsOn(cleanComplianceRepositories)
        }
}

val generateComplianceSbom =
    tasks.register<Exec>("generateComplianceSbom") {
        group = "compliance"
        description = "Generates the checked-in distribution SBOM from declared components."
        inputs.files(
            layout.projectDirectory.file("compliance/ffmpeg/manifest.json"),
            layout.projectDirectory.file("gradle/libs.versions.toml"),
            layout.projectDirectory.file("scripts/generate_sbom.py"),
        )
        desktopNativePayloadDirectory.orNull?.let { inputs.dir(it) }
        androidNativePayloadDirectory.orNull?.let { inputs.dir(it) }
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
        desktopNativePayloadDirectory.orNull?.let { directory ->
            arguments += listOf("--runtime-resources", directory)
        }
        androidNativePayloadDirectory.orNull?.let { directory ->
            arguments += listOf("--android-runtime", directory)
        }
        commandLine(arguments)
    }

val verifyCompliance =
    tasks.register<Exec>("verifyCompliance") {
        group = "verification"
        description = "Fails closed when licensing or FFmpeg source-offer evidence is incomplete."
        inputs.files(
            fileTree("api/src"),
            fileTree("ffmpeg/src"),
            fileTree("ffmpeg-runtime-desktop/src"),
            fileTree("ffmpeg-runtime-android/src"),
            fileTree("native"),
            fileTree("scripts") {
                exclude("__pycache__/**")
            },
            fileTree("compliance"),
            fileTree(".github/workflows"),
            fileTree("LICENSES"),
            layout.projectDirectory.file("build.gradle.kts"),
            layout.projectDirectory.file("settings.gradle.kts"),
            layout.projectDirectory.file("api/build.gradle.kts"),
            layout.projectDirectory.file("ffmpeg/build.gradle.kts"),
            layout.projectDirectory.file("ffmpeg-runtime-desktop/build.gradle.kts"),
            layout.projectDirectory.file("ffmpeg-runtime-android/build.gradle.kts"),
            layout.projectDirectory.file("gradle/libs.versions.toml"),
            layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar"),
            layout.projectDirectory.file("gradle/wrapper/LICENSE"),
            layout.projectDirectory.file("LICENSE"),
            layout.projectDirectory.file("NOTICE"),
            layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
        )
        commandLine(
            pythonExecutable,
            "scripts/verify_compliance.py",
            "--root",
            layout.projectDirectory.asFile.absolutePath,
        )
    }

val testRuntimeInspectionLogic =
    tasks.register<Exec>("testRuntimeInspectionLogic") {
        group = "verification"
        description = "Runs regression tests for native runtime dependency inspection."
        inputs.files(
            layout.projectDirectory.file("scripts/inspect_native_runtime.py"),
            layout.projectDirectory.file("scripts/test_inspect_native_runtime.py"),
        )
        commandLine(
            pythonExecutable,
            "-m",
            "unittest",
            "discover",
            "-s",
            "scripts",
            "-p",
            "test_*.py",
        )
    }

val verifyPublications =
    tasks.register<Exec>("verifyPublications") {
        group = "verification"
        description = "Verifies the internal-use core and LGPL runtime publication boundaries."
        dependsOn(
            ":api:publishAllPublicationsToInternalCoreComplianceRepository",
            ":ffmpeg:publishAllPublicationsToInternalCoreComplianceRepository",
            ":ffmpeg-runtime-desktop:publishAllPublicationsToRuntimeComplianceRepository",
            ":ffmpeg-runtime-android:publishAllPublicationsToRuntimeComplianceRepository",
        )
        inputs.dir(layout.buildDirectory.dir("internal-core-compliance-repository"))
        inputs.dir(layout.buildDirectory.dir("runtime-compliance-repository"))
        commandLine(
            pythonExecutable,
            "scripts/verify_publications.py",
            "--internal-core-repository",
            layout.buildDirectory
                .dir("internal-core-compliance-repository")
                .get()
                .asFile.absolutePath,
            "--runtime-repository",
            layout.buildDirectory
                .dir("runtime-compliance-repository")
                .get()
                .asFile.absolutePath,
            "--version",
            publicationVersion.get(),
        )
    }

tasks.named("check") {
    dependsOn(
        ":api:check",
        ":ffmpeg:check",
        ":ffmpeg-runtime-desktop:check",
        ":ffmpeg-runtime-android:check",
    )
    dependsOn(verifyCompliance)
    dependsOn(generateComplianceSbom)
    dependsOn(testRuntimeInspectionLogic)
}

tasks.register("complianceCheck") {
    group = "verification"
    dependsOn(verifyCompliance)
    dependsOn(verifyPublications)
    dependsOn(generateComplianceSbom)
    dependsOn(testRuntimeInspectionLogic)
}
