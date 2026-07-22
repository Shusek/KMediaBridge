// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyDesktopClientPayload : DefaultTask() {
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val payload: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val verifier: RegularFileProperty

    @TaskAction
    fun verify() {
        if (!payload.isPresent) return
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val status =
            ProcessBuilder(
                python,
                verifier.get().asFile.absolutePath,
                "--resources",
                payload.get().asFile.absolutePath,
            ).inheritIO().start().waitFor()
        require(status == 0) { "Desktop KMediaBridge client payload verification failed." }
    }
}

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.maven.publish)
}

val ffmpegRuntimeVersion = "0.1.0-rc.1"
val nativePayload =
    providers
        .gradleProperty("kmediaBridgeDesktopNativePayloadDirectory")
        .orElse(providers.gradleProperty("desktopNativePayloadDirectory"))
        .map(rootProject::file)

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

dependencies {
    api("io.github.shusek:kmedia-ffmpeg-runtime-desktop:$ffmpegRuntimeVersion") {
        version { strictly(ffmpegRuntimeVersion) }
    }
}

sourceSets.main {
    nativePayload.orNull?.let { resources.srcDir(it) }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<Jar>("sourcesJar") { exclude("META-INF/kmediabridge/native/**") }

tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
}

val verifyNativePayload =
    tasks.register<VerifyDesktopClientPayload>("verifyNativePayload") {
        payload.set(layout.dir(nativePayload))
        verifier.set(rootProject.layout.projectDirectory.file("scripts/verify_client_resources.py"))
    }

tasks.named("check") { dependsOn(verifyNativePayload) }
tasks.matching { it.name.startsWith("publish", ignoreCase = true) }.configureEach {
    dependsOn(verifyNativePayload)
    doFirst { require(nativePayload.isPresent) { "Publishing requires -PkmediaBridgeDesktopNativePayloadDirectory." } }
}

publishing.repositories {
    rootProject.providers.gradleProperty("releaseRepository").orNull?.let { repositoryPath ->
        maven {
            name = "release"
            url = uri(repositoryPath)
        }
    }
    maven {
        name = "runtimeCompliance"
        url = rootProject.layout.buildDirectory.dir("runtime-compliance-repository").get().asFile.toURI()
    }
}

mavenPublishing {
    coordinates("io.github.shusek", "kmedia-bridge-client-desktop", project.version.toString())
    pom {
        name.set("KMediaBridge Native Client for Desktop")
        description.set("Private KMediaBridge JNA client linked to the shared KMediaFfmpegRuntime ABI.")
        inceptionYear.set("2026")
        url.set("https://github.com/Shusek/KMediaBridge")
        licenses {
            license {
                name.set("KMediaBridge Internal Use Notice and Limited License")
                url.set("https://github.com/Shusek/KMediaBridge/blob/main/LICENSES/LicenseRef-KMediaBridge-Internal.txt")
                distribution.set("repo")
            }
        }
        developers { developer { id.set("Shusek"); name.set("Shusek") } }
        scm { url.set("https://github.com/Shusek/KMediaBridge") }
    }
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
}
