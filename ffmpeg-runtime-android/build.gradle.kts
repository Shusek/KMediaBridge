// SPDX-License-Identifier: LGPL-2.1-or-later

import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.Properties

abstract class VerifyAndroidNativePayload : DefaultTask() {
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativePayloadDirectory: DirectoryProperty

    @get:Input
    abstract val requestedAbis: ListProperty<String>

    @get:Input
    abstract val expectedLibraries: ListProperty<String>

    @get:Input
    abstract val payloadRequired: Property<Boolean>

    @TaskAction
    fun verify() {
        if (!nativePayloadDirectory.isPresent) {
            require(!payloadRequired.get()) {
                "Publishing the stable Android runtime requires " +
                    "-PandroidNativePayloadDirectory=<native-dist-android>."
            }
            return
        }
        val payloadRoot = nativePayloadDirectory.get().asFile
        val manifest = payloadRoot.resolve("compliance/manifest.properties")
        require(manifest.isFile) { "The Android runtime compliance manifest is missing." }
        val properties = Properties().apply { manifest.inputStream().use(::load) }
        require(properties.getProperty("abiVersion") == "4") {
            "The Android runtime ABI is not supported."
        }
        require(properties.getProperty("ffmpegVersion") == "8.1.2") {
            "The Android FFmpeg version is not pinned."
        }
        require(properties.getProperty("ffmpegLicenseSpdx") == "LGPL-2.1-or-later") {
            "The bundled Android FFmpeg runtime is not declared LGPL-only."
        }
        require(properties.getProperty("dynamicLinking") == "true") {
            "The Android runtime does not declare the replaceable dynamic FFmpeg boundary."
        }
        require(properties.getProperty("dynamicLinkingVerified") == "true") {
            "The Android runtime was not inspected as a dynamically linked payload."
        }
        require(properties.getProperty("feature.hdrToSdrToneMap") == "true") {
            "The Android runtime does not contain the controlled HDR-to-SDR feature."
        }
        requestedAbis.get().forEach { abi ->
            val directory = payloadRoot.resolve("jniLibs/$abi")
            require(directory.isDirectory) { "The Android runtime has no $abi payload." }
            require(directory.listFiles()?.map(File::getName)?.toSet() == expectedLibraries.get().toSet()) {
                "The Android $abi payload has an incomplete or unexpected native library set."
            }
            expectedLibraries.get().forEach { name ->
                require(directory.resolve(name).length() > 0L) {
                    "The Android native library $abi/$name is empty."
                }
            }
        }
    }
}

abstract class GenerateAndroidRuntimeResources : DefaultTask() {
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativePayloadDirectory: DirectoryProperty

    @get:Input
    abstract val requestedAbis: ListProperty<String>

    @get:Input
    abstract val expectedLibraries: ListProperty<String>

    @get:Input
    abstract val buildRecipeRevision: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lgplLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeNotice: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val resourcesRoot = outputDirectory.get().asFile
        resourcesRoot.deleteRecursively()
        val output =
            resourcesRoot.resolve(
                "META-INF/kmediabridge/android-runtime.properties",
            )
        output.parentFile.mkdirs()
        val payloadRoot =
            nativePayloadDirectory.orNull
                ?.asFile
                ?.takeIf(File::isDirectory)
        val payloadAvailable = payloadRoot != null
        val lines =
            mutableListOf(
                "schemaVersion=1",
                "available=$payloadAvailable",
                "abiVersion=4",
                "ffmpegVersion=8.1.2",
                "ffmpegLicenseSpdx=LGPL-2.1-or-later",
                "ffmpegSourceArchiveUrl=https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz",
                "ffmpegSourceArchiveSha256=464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c",
                "buildRecipeUrl=https://github.com/Shusek/KMediaBridge/tree/${buildRecipeRevision.get()}/native",
                "buildRecipeRevision=${buildRecipeRevision.get()}",
                "exactCorrespondingSourceAvailable=true",
                "dynamicLinkingVerified=$payloadAvailable",
                "feature.hdrToSdrToneMap=true",
                "feature.subtitleBurnIn=false",
                "abi.count=${requestedAbis.get().size}",
            )
        requestedAbis.get().forEachIndexed { index, abi ->
            lines += "abi.$index.name=$abi"
            expectedLibraries.get().forEach { name ->
                val library = payloadRoot?.resolve("jniLibs/$abi/$name")
                if (library?.isFile == true) {
                    lines += "abi.$index.$name.sha256=${sha256(library)}"
                }
            }
        }
        output.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        listOf(
            resourcesRoot.resolve("META-INF/LICENSE"),
            resourcesRoot.resolve("META-INF/kmediabridge/LICENSE"),
        ).forEach { target ->
            target.parentFile.mkdirs()
            lgplLicense.get().asFile.copyTo(target, overwrite = true)
        }
        runtimeNotice.get().asFile.copyTo(
            resourcesRoot.resolve("META-INF/NOTICE"),
            overwrite = true,
        )
        thirdPartyNotices.get().asFile.copyTo(
            resourcesRoot.resolve("META-INF/THIRD_PARTY_NOTICES.md"),
            overwrite = true,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

val nativePayloadPath =
    providers.gradleProperty("androidNativePayloadDirectory")
val selectedNativePayloadDirectory = layout.dir(nativePayloadPath.map(rootProject::file))
val requestedRuntimeAbis =
    providers
        .gradleProperty("androidRuntimeAbis")
        .orElse("arm64-v8a,armeabi-v7a,x86_64")
        .map { value -> value.split(',').map(String::trim).filter(String::isNotBlank).distinct() }
val recipeRevision = providers.gradleProperty("buildRecipeRevision").orElse("uncommitted-source")
val stablePublication =
    providers.provider {
        project.version.toString().matches(Regex("""\d+\.\d+\.\d+"""))
    }
val generatedResources = layout.buildDirectory.dir("generated/androidRuntimeResources")
val lgplLicenseFile = rootProject.layout.projectDirectory.file("LICENSES/LGPL-2.1-or-later.txt")
val runtimeNoticeFile =
    layout.projectDirectory.file(
        "src/main/resources/META-INF/kmediabridge/NOTICE",
    )
val thirdPartyNoticesFile = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")

val runtimeLibraryNames =
    listOf(
        "libavutil-kmb.so",
        "libavcodec-kmb.so",
        "libavformat-kmb.so",
        "libswscale-kmb.so",
        "libkmediabridge.so",
    )

val verifyNativePayload =
    tasks.register<VerifyAndroidNativePayload>("verifyNativePayload") {
        group = "verification"
        description = "Verifies the Android FFmpeg payload before a stable runtime may be published."
        nativePayloadDirectory.set(selectedNativePayloadDirectory)
        requestedAbis.set(requestedRuntimeAbis)
        expectedLibraries.set(runtimeLibraryNames)
        payloadRequired.set(stablePublication)
    }

val generateAndroidRuntimeResources =
    tasks.register<GenerateAndroidRuntimeResources>("generateAndroidRuntimeResources") {
        nativePayloadDirectory.set(selectedNativePayloadDirectory)
        requestedAbis.set(requestedRuntimeAbis)
        expectedLibraries.set(runtimeLibraryNames)
        buildRecipeRevision.set(recipeRevision)
        lgplLicense.set(lgplLicenseFile)
        runtimeNotice.set(runtimeNoticeFile)
        thirdPartyNotices.set(thirdPartyNoticesFile)
        outputDirectory.set(generatedResources)
    }

extensions.configure<LibraryExtension> {
    namespace = "io.github.shusek.kmediabridge.ffmpeg.runtime.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    sourceSets.getByName("main") {
        selectedNativePayloadDirectory.orNull?.let {
            jniLibs.directories.add(it.asFile.resolve("jniLibs").absolutePath)
        }
        resources.directories.add(generatedResources.get().asFile.absolutePath)
    }
}

tasks.named("preBuild") {
    dependsOn(generateAndroidRuntimeResources)
}

tasks.matching { task -> task.name.startsWith("publish", ignoreCase = true) }.configureEach {
    dependsOn(verifyNativePayload)
}

publishing {
    repositories {
        maven {
            name = "runtimeCompliance"
            url =
                rootProject.layout.buildDirectory
                    .dir("runtime-compliance-repository")
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
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "kmedia-bridge-ffmpeg-runtime-android",
        version = project.version.toString(),
    )

    pom {
        name.set("KMediaBridge Bundled FFmpeg Runtime for Android")
        description.set("Optional dynamically linked LGPL FFmpeg/MediaCodec runtime for KMediaBridge on Android.")
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
