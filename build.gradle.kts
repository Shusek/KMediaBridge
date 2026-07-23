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

val publicationVersion = providers.gradleProperty("publicationVersion").orElse("0.5.0-SNAPSHOT")
val pythonExecutable = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"

allprojects {
    group = "io.github.shusek"
    version = publicationVersion.get()
}

ktlint { version.set("1.7.1") }

val verifyCompliance =
    tasks.register<Exec>("verifyCompliance") {
        group = "verification"
        description = "Verifies the shared-runtime client boundary and publication policy."
        inputs.files(
            fileTree("api/src"),
            fileTree("ffmpeg/src"),
            fileTree("ffmpeg-runtime-desktop/src"),
            fileTree("ffmpeg-runtime-android/src"),
            fileTree("native"),
            fileTree("scripts") { exclude("__pycache__/**") },
            fileTree(".github/workflows"),
            fileTree("LICENSES"),
            layout.projectDirectory.file("build.gradle.kts"),
            layout.projectDirectory.file("settings.gradle.kts"),
            layout.projectDirectory.file("LICENSE"),
            layout.projectDirectory.file("NOTICE"),
            layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
        )
        commandLine(pythonExecutable, "scripts/verify_compliance.py", "--root", layout.projectDirectory.asFile.absolutePath)
    }

val testScripts =
    tasks.register<Exec>("testScripts") {
        group = "verification"
        description = "Runs client packaging and native inspection regression tests."
        inputs.files(
            fileTree("scripts") {
                include("*.py")
                exclude("__pycache__/**")
            },
        )
        commandLine(pythonExecutable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py")
    }

tasks.named("check") {
    dependsOn(":api:check", ":ffmpeg:check", ":ffmpeg-runtime-desktop:check", ":ffmpeg-runtime-android:check")
    dependsOn(verifyCompliance, testScripts)
}

tasks.register("complianceCheck") {
    group = "verification"
    dependsOn(verifyCompliance, testScripts)
}
