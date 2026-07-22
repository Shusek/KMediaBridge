// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import java.util.Properties
import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyAndroidClientPayload : DefaultTask() {
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val payload: DirectoryProperty

    @TaskAction
    fun verify() {
        if (!payload.isPresent) return
        val root = payload.get().asFile
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Android client payload must be a real directory."
        }
        val expectedAbis = setOf("arm64-v8a", "armeabi-v7a")
        val actualAbis = root.resolve("jniLibs").listFiles().orEmpty().filter(File::isDirectory).map(File::getName).toSet()
        require(actualAbis == expectedAbis) { "Android client ABI set differs: $actualAbis" }
        expectedAbis.forEach { abi ->
            val files = root.resolve("jniLibs/$abi").listFiles().orEmpty()
            require(files.map(File::getName).toSet() == setOf("libkmediabridge.so") && files.single().length() > 0L) {
                "Android $abi must contain exactly libkmediabridge.so."
            }
        }
        val manifest = root.resolve("android-client.properties")
        require(manifest.isFile) { "Android client manifest is missing." }
        val properties = Properties().apply { manifest.inputStream().use(::load) }
        require(properties.getProperty("sharedRuntimeId")?.matches(
            Regex("kmediaffmpeg-8\\.1\\.2-ass-0\\.17\\.4-[0-9a-f]{16}"),
        ) == true) { "Android client manifest has an invalid shared runtime ID." }
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

val ffmpegRuntimeVersion = "0.1.0-rc.2"
val nativePayload =
    providers
        .gradleProperty("kmediaBridgeAndroidNativePayloadDirectory")
        .orElse(providers.gradleProperty("androidNativePayloadDirectory"))
        .map(rootProject::file)
val generatedResources = layout.buildDirectory.dir("generated/androidClientResources")

extensions.configure<LibraryExtension> {
    namespace = "io.github.shusek.kmediabridge.client.android"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    sourceSets.getByName("main") {
        nativePayload.orNull?.let { jniLibs.directories.add(it.resolve("jniLibs").absolutePath) }
        resources.directories.add(generatedResources.get().asFile.absolutePath)
    }
}

dependencies {
    api("io.github.shusek:kmedia-ffmpeg-runtime-android:$ffmpegRuntimeVersion") {
        version { strictly(ffmpegRuntimeVersion) }
    }
}

val prepareClientResources =
    tasks.register<Sync>("prepareClientResources") {
        into(generatedResources)
        from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF/kmediabridge") }
        nativePayload.orNull?.let { payload ->
            from(payload.resolve("android-client.properties")) {
                into("META-INF/kmediabridge")
            }
        }
    }

val verifyNativePayload =
    tasks.register<VerifyAndroidClientPayload>("verifyNativePayload") {
        payload.set(layout.dir(nativePayload))
    }

tasks.named("preBuild") { dependsOn(prepareClientResources, verifyNativePayload) }
tasks.matching { it.name.startsWith("publish", ignoreCase = true) }.configureEach {
    dependsOn(verifyNativePayload)
    doFirst { require(nativePayload.isPresent) { "Publishing requires -PkmediaBridgeAndroidNativePayloadDirectory." } }
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
    configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
    coordinates("io.github.shusek", "kmedia-bridge-client-android", project.version.toString())
    pom {
        name.set("KMediaBridge Native Client for Android")
        description.set("Private KMediaBridge JNI client linked to the shared KMediaFfmpegRuntime ABI.")
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
