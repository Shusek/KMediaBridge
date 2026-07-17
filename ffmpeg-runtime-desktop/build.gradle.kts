// SPDX-License-Identifier: LGPL-2.1-or-later

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.maven.publish)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

val nativePayloadDirectory = providers.gradleProperty("nativePayloadDirectory")

sourceSets {
    main {
        nativePayloadDirectory.orNull?.let { resources.srcDir(rootProject.file(it)) }
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<ProcessResources>("processResources") {
    from(
        rootProject.layout.projectDirectory
            .file("LICENSES/LGPL-2.1-or-later.txt"),
    ) {
        into("META-INF")
        rename { "LICENSE" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
}

publishing {
    repositories {
        maven {
            name = "publicCompliance"
            url =
                rootProject.layout.buildDirectory
                    .dir("public-compliance-repository")
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
        artifactId = "kmedia-bridge-ffmpeg-runtime-desktop",
        version = project.version.toString(),
    )

    pom {
        name.set("KMediaBridge Bundled FFmpeg Runtime for Desktop")
        description.set("Optional dynamically linked LGPL FFmpeg runtime for KMediaBridge on desktop JVM.")
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
