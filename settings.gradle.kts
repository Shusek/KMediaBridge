// SPDX-License-Identifier: LGPL-2.1-or-later

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KMediaBridge"

val sharedRuntimeProjectDir = providers.gradleProperty("kmediaFfmpegRuntimeProjectDir").orNull
if (sharedRuntimeProjectDir != null) {
    includeBuild(sharedRuntimeProjectDir) {
        dependencySubstitution {
            substitute(module("io.github.shusek:kmedia-ffmpeg-runtime-android"))
                .using(project(":kmedia-ffmpeg-runtime-android"))
            substitute(module("io.github.shusek:kmedia-ffmpeg-runtime-desktop"))
                .using(project(":kmedia-ffmpeg-runtime-desktop"))
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        providers.gradleProperty("kmediaFfmpegRuntimeRepository").orNull?.let { repositoryPath ->
            maven { url = uri(repositoryPath) }
        }
        mavenCentral()
    }
}

include(":api")
include(":ffmpeg")
include(":ffmpeg-runtime-desktop")
include(":ffmpeg-runtime-android")
