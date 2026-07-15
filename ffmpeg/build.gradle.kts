// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    android {
        namespace = "io.github.shusek.kmediabridge.ffmpeg"
        compileSdk = 37
        minSdk = 23
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    @Suppress("DEPRECATION")
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            runtimeOnly(project(":ffmpeg-runtime-desktop"))
        }
    }
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("kmediaBridgeTestMedia").orNull?.let { mediaPath ->
        systemProperty("kmediabridge.testMedia", mediaPath)
    }
}

publishing {
    repositories {
        maven {
            name = "compliance"
            url =
                rootProject.layout.buildDirectory
                    .dir("compliance-repository")
                    .get()
                    .asFile
                    .toURI()
        }
        rootProject.providers.gradleProperty("githubPagesMavenRepository").orNull?.let { repositoryPath ->
            maven {
                name = "githubPages"
                url = uri(repositoryPath)
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "kmedia-bridge-ffmpeg",
        version = project.version.toString(),
    )

    pom {
        name.set("KMediaBridge FFmpeg Backend")
        description.set("Optional compliance-gated FFmpeg backend and desktop JVM native loader for KMediaBridge.")
        inceptionYear.set("2026")
        url.set("https://github.com/Shusek/KMediaBridge")

        licenses {
            license {
                name.set("GNU Lesser General Public License v2.1 or later")
                url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Shusek")
                name.set("Shusek")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/Shusek/KMediaBridge.git")
            developerConnection.set("scm:git:ssh://git@github.com/Shusek/KMediaBridge.git")
            url.set("https://github.com/Shusek/KMediaBridge")
        }
    }

    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
