// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal
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
        namespace = "io.github.shusek.kmediabridge.api"
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
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

publishing {
    repositories {
        maven {
            name = "internalCoreCompliance"
            url =
                rootProject.layout.buildDirectory
                    .dir("internal-core-compliance-repository")
                    .get()
                    .asFile
                    .toURI()
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "kmedia-bridge-api",
        version = project.version.toString(),
    )

    pom {
        name.set("KMediaBridge API")
        description.set("Engine-neutral Kotlin Multiplatform contracts for media probing and container bridging.")
        inceptionYear.set("2026")
        url.set("https://github.com/Shusek/KMediaBridge")

        licenses {
            license {
                name.set(
                    "KMediaBridge Internal Use Notice and Limited License " +
                        "(LicenseRef-KMediaBridge-Internal)",
                )
                url.set(
                    "https://github.com/Shusek/KMediaBridge/blob/main/" +
                        "LICENSES/LicenseRef-KMediaBridge-Internal.txt",
                )
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
