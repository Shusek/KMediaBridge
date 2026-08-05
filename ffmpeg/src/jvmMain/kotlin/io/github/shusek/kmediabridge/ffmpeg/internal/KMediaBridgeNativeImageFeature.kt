// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg.internal

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeJNIAccess
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess
import java.util.Locale
import java.util.Properties

/** Native Image registrations for the bundled desktop JNA and FFmpeg runtimes. */
internal class KMediaBridgeNativeImageFeature : Feature {
    override fun duringSetup(access: Feature.DuringSetupAccess) {
        nativeRuntimePlatform()?.let { platform ->
            registerBundledRuntimeResources(access, platform.id)
            registerJnaDispatcher(access, platform.jnaDispatcherResource)
        }
        registerNativeProbeJni(access)
    }

    private fun registerBundledRuntimeResources(
        access: Feature.DuringSetupAccess,
        platform: String,
    ) {
        runtimeBundles(platform).forEach { bundle ->
            val anchorType = access.findClassByName(bundle.anchorClassName) ?: return@forEach
            val descriptorPath = "${bundle.resourceRoot}/${bundle.descriptorName}"
            val properties =
                Properties().apply {
                    val descriptor =
                        access.applicationClassLoader.getResourceAsStream(descriptorPath)
                            ?: return@forEach
                    descriptor.use(::load)
                }

            RuntimeResourceAccess.addResource(anchorType.module, descriptorPath)
            bundle.libraryNames(properties).forEach { libraryName ->
                val libraryPath =
                    listOfNotNull(bundle.libraryDirectory, libraryName)
                        .joinToString("/")
                RuntimeResourceAccess.addResource(
                    anchorType.module,
                    "${bundle.resourceRoot}/$libraryPath",
                )
            }
        }
    }

    private fun registerJnaDispatcher(
        access: Feature.DuringSetupAccess,
        resourcePath: String,
    ) {
        val jnaType = access.findClassByName(JNA_NATIVE_CLASS_NAME) ?: return
        if (access.applicationClassLoader.getResource(resourcePath) == null) return
        RuntimeResourceAccess.addResource(jnaType.module, resourcePath)
    }

    private fun registerNativeProbeJni(access: Feature.DuringSetupAccess) {
        NATIVE_PROBE_CLASS_NAMES.forEach { className ->
            val probeType = access.findClassByName(className) ?: return@forEach
            RuntimeJNIAccess.register(probeType)
            probeType.declaredConstructors.forEach { RuntimeJNIAccess.register(it) }
            probeType.declaredMethods.forEach { RuntimeJNIAccess.register(it) }
        }
    }

    private fun nativeRuntimePlatform(): NativeRuntimePlatform? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val normalizedArch =
            when (arch) {
                "aarch64", "arm64" -> "aarch64"
                "amd64", "x86_64" -> "x86_64"
                else -> return null
            }
        return when {
            (os.contains("mac") || os.contains("darwin")) && normalizedArch == "aarch64" ->
                NativeRuntimePlatform(
                    id = "macos-aarch64",
                    jnaDispatcherResource = "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib",
                )
            os.contains("linux") ->
                NativeRuntimePlatform(
                    id = "linux-$normalizedArch",
                    jnaDispatcherResource =
                        if (normalizedArch == "aarch64") {
                            "com/sun/jna/linux-aarch64/libjnidispatch.so"
                        } else {
                            "com/sun/jna/linux-x86-64/libjnidispatch.so"
                        },
                )
            os.contains("win") && normalizedArch == "x86_64" ->
                NativeRuntimePlatform(
                    id = "windows-x86_64",
                    jnaDispatcherResource = "com/sun/jna/win32-x86-64/jnidispatch.dll",
                )
            else -> null
        }
    }

    private fun runtimeBundles(platform: String): List<NativeRuntimeBundle> =
        listOf(
            NativeRuntimeBundle(
                anchorClassName = "io.github.shusek.kmediabridge.ffmpeg.BundledFfmpegNativeDriver",
                resourceRoot = "META-INF/kmediabridge/native/$platform",
                descriptorName = "manifest.properties",
                indexedLibraryNames = true,
            ),
            NativeRuntimeBundle(
                anchorClassName = "io.github.shusek.kmediaffmpeg.runtime.KMediaAssRuntime",
                resourceRoot = "META-INF/kmediaass/native/$platform",
                descriptorName = "ass-runtime.properties",
                libraryDirectory = "lib",
            ),
            NativeRuntimeBundle(
                anchorClassName = "io.github.shusek.kmediaffmpeg.runtime.KMediaFfmpegRuntime",
                resourceRoot = "META-INF/kmediaffmpeg/native/$platform",
                descriptorName = "runtime.properties",
                libraryDirectory = "lib",
            ),
        )

    private data class NativeRuntimePlatform(
        val id: String,
        val jnaDispatcherResource: String,
    )

    private data class NativeRuntimeBundle(
        val anchorClassName: String,
        val resourceRoot: String,
        val descriptorName: String,
        val libraryDirectory: String? = null,
        val indexedLibraryNames: Boolean = false,
    ) {
        fun libraryNames(properties: Properties): List<String> =
            if (indexedLibraryNames) {
                val count = requireNotNull(properties.getProperty("library.count")).toInt()
                List(count) { index ->
                    requireNotNull(properties.getProperty("library.$index.name"))
                }
            } else {
                requireNotNull(properties.getProperty("libraries"))
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }
    }

    private companion object {
        const val JNA_NATIVE_CLASS_NAME: String = "com.sun.jna.Native"
        val NATIVE_PROBE_CLASS_NAMES: List<String> =
            listOf(
                "io.github.shusek.kmediaffmpeg.runtime.AssNativeProbe",
                "io.github.shusek.kmediaffmpeg.runtime.NativeProbe",
            )
    }
}
