// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge.ffmpeg

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

internal fun interface KmbWriteCallback : Callback {
    fun invoke(
        opaque: Pointer?,
        bytes: Pointer?,
        size: Int,
    ): Int
}

internal interface KmbNativeApi : Library {
    fun kmb_abi_version(): Int

    fun kmb_ffmpeg_version(): Pointer?

    fun kmb_ffmpeg_license(): Pointer?

    fun kmb_ffmpeg_configuration(): Pointer?

    fun kmb_probe_json(
        inputLocator: String,
        outputJson: PointerByReference,
        outputError: PointerByReference,
    ): Int

    fun kmb_remux_fragmented_mp4_stream(
        inputLocator: String,
        fragmentDurationUs: Long,
        startTimeUs: Long,
        writeCallback: KmbWriteCallback,
        opaque: Pointer?,
        outputError: PointerByReference,
    ): Int

    fun kmb_free_string(value: Pointer?)
}

internal class LoadedFfmpegRuntime(
    private val api: KmbNativeApi,
    @Suppress("unused") private val retainedLibraries: List<NativeLibrary>,
    val runtimeInfo: FfmpegRuntimeInfo,
) {
    fun probeJson(inputLocator: String): String {
        val output = PointerByReference()
        val error = PointerByReference()
        val result = api.kmb_probe_json(inputLocator, output, error)
        val errorText = takeOwnedString(error.value)
        if (result != KMB_OK) {
            takeOwnedString(output.value)
            throw MediaBridgeException(
                MediaBridgeErrorCode.PROBE_FAILED,
                errorText.ifBlank { "The native FFmpeg probe failed without exposing the input locator." },
            )
        }
        return takeOwnedString(output.value)
    }

    fun remuxFragmentedMp4(
        inputLocator: String,
        fragmentDurationUs: Long,
        startTimeUs: Long,
        consumer: (ByteArray) -> Boolean,
    ) {
        val outputError = PointerByReference()
        val callbackFailure = AtomicReference<Throwable?>(null)
        val callback =
            KmbWriteCallback { _, pointer, size ->
                try {
                    if (pointer == null || size <= 0 || !consumer(pointer.getByteArray(0L, size))) 1 else 0
                } catch (failure: Throwable) {
                    callbackFailure.compareAndSet(null, failure)
                    1
                }
            }
        val result =
            api.kmb_remux_fragmented_mp4_stream(
                inputLocator,
                fragmentDurationUs,
                startTimeUs,
                callback,
                null,
                outputError,
            )
        val errorText = takeOwnedString(outputError.value)
        callbackFailure.get()?.let { throw it }
        if (result != KMB_OK && result != KMB_CANCELLED) {
            throw MediaBridgeException(
                MediaBridgeErrorCode.CONVERSION_FAILED,
                errorText.ifBlank { "The native FFmpeg remux operation failed without exposing the input locator." },
            )
        }
    }

    private fun takeOwnedString(pointer: Pointer?): String {
        if (pointer == null) return ""
        return try {
            pointer.getString(0L, Charsets.UTF_8.name())
        } finally {
            api.kmb_free_string(pointer)
        }
    }

    private companion object {
        const val KMB_OK: Int = 0
        const val KMB_CANCELLED: Int = 9
    }
}

internal object DesktopRuntimeLoader {
    private const val SUPPORTED_ABI = 2
    private const val MANIFEST_NAME = "manifest.properties"

    fun load(
        replacementDirectory: Path?,
        extractionParentDirectory: Path?,
        classLoader: ClassLoader,
    ): LoadedFfmpegRuntime {
        val platform = DesktopPlatform.detect()
        val source =
            if (replacementDirectory == null) {
                RuntimeSource.Embedded(platform, classLoader, extractionParentDirectory)
            } else {
                RuntimeSource.Replacement(platform, replacementDirectory)
            }
        val manifest = source.readManifest()
        if (manifest.platform != platform.id) {
            reject("The runtime manifest targets ${manifest.platform}, but this JVM requires ${platform.id}.")
        }
        if (manifest.abiVersion != SUPPORTED_ABI) {
            reject("The runtime manifest declares unsupported ABI ${manifest.abiVersion}.")
        }

        val directory = source.materialize(manifest)
        val resolvedLibraries = verifyLibraries(directory, manifest)
        val bridge = manifest.libraries.singleOrNull { it.role == LibraryRole.BRIDGE }
            ?: reject("The runtime manifest must declare exactly one bridge library.")
        val (retained, api) =
            try {
                val dependencies =
                    manifest.libraries
                        .filter { it.role == LibraryRole.DEPENDENCY }
                        .map { library -> NativeLibrary.getInstance(resolvedLibraries.getValue(library.name).toString()) }
                dependencies to
                    Native.load(
                        resolvedLibraries.getValue(bridge.name).toString(),
                        KmbNativeApi::class.java,
                        mapOf(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name()),
                    )
            } catch (error: LinkageError) {
                throw MediaBridgeException(
                    MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                    "The verified native runtime could not be loaded.",
                    error,
                )
            }

        val actualAbi = api.kmb_abi_version()
        if (actualAbi != SUPPORTED_ABI) reject("The loaded runtime reported unsupported ABI $actualAbi.")
        val actualVersion = api.borrowedString(api.kmb_ffmpeg_version())
        val actualLicense = api.borrowedString(api.kmb_ffmpeg_license())
        val actualConfiguration = api.borrowedString(api.kmb_ffmpeg_configuration())
        if (actualVersion != manifest.ffmpegVersion) {
            reject("The loaded FFmpeg version does not match the signed runtime manifest.")
        }
        if (actualLicense != manifest.ffmpegReportedLicense) {
            reject("The loaded FFmpeg license does not match the signed runtime manifest.")
        }

        val runtimeInfo =
            FfmpegRuntimeInfo(
                ffmpegVersion = actualVersion,
                ffmpegLicenseSpdx = manifest.ffmpegLicenseSpdx,
                ffmpegReportedLicense = actualLicense,
                configureArguments = actualConfiguration.split(Regex("\\s+")).filter(String::isNotBlank),
                ffmpegSourceArchiveUrl = manifest.sourceOfferUrl,
                ffmpegSourceArchiveSha256 = manifest.sourceSha256,
                nativeArtifactSha256 = bridge.sha256,
                buildRecipeUrl = manifest.buildRecipeUrl,
                buildRecipeRevision = manifest.buildRecipeRevision,
                exactCorrespondingSourceAvailable = manifest.exactCorrespondingSourceAvailable,
                dynamicLinkingVerified = manifest.dynamicLinkingVerified,
            )
        FfmpegComplianceVerifier.requireCompliant(runtimeInfo)
        return LoadedFfmpegRuntime(api, retained, runtimeInfo)
    }

    private fun verifyLibraries(
        directory: Path,
        manifest: NativePayloadManifest,
    ): Map<String, Path> {
        val realDirectory = directory.toRealPath()
        return manifest.libraries.associate { library ->
            requireSimpleName(library.name)
            val path = realDirectory.resolve(library.name)
            val realPath =
                try {
                    path.toRealPath()
                } catch (error: Exception) {
                    throw MediaBridgeException(
                        MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                        "A native library declared by the runtime manifest is missing.",
                        error,
                    )
                }
            if (!realPath.startsWith(realDirectory) || !Files.isRegularFile(realPath)) {
                reject("A declared native library escapes the selected runtime directory.")
            }
            if (sha256(realPath) != library.sha256) {
                reject("A native library SHA-256 does not match the runtime manifest.")
            }
            library.name to realPath
        }
    }

    private fun KmbNativeApi.borrowedString(pointer: Pointer?): String =
        pointer?.getString(0L, Charsets.UTF_8.name()).orEmpty()

    private sealed interface RuntimeSource {
        fun readManifest(): NativePayloadManifest

        fun materialize(manifest: NativePayloadManifest): Path

        class Embedded(
            private val platform: DesktopPlatform,
            private val classLoader: ClassLoader,
            private val extractionParentDirectory: Path?,
        ) : RuntimeSource {
            private val prefix = "META-INF/kmediabridge/native/${platform.id}"

            override fun readManifest(): NativePayloadManifest {
                val stream = classLoader.getResourceAsStream("$prefix/$MANIFEST_NAME")
                    ?: throw MediaBridgeException(
                        MediaBridgeErrorCode.UNSUPPORTED_REQUEST,
                        "No bundled FFmpeg payload was found for ${platform.id}. Add " +
                            "io.github.shusek:kmedia-bridge-ffmpeg-runtime-desktop at runtime.",
                    )
                return stream.use(NativePayloadManifest::read)
            }

            override fun materialize(manifest: NativePayloadManifest): Path {
                val parent =
                    extractionParentDirectory?.toAbsolutePath()?.normalize()?.also(Files::createDirectories)
                val directory =
                    if (parent == null) {
                        Files.createTempDirectory("kmediabridge-${platform.id}-")
                    } else {
                        Files.createTempDirectory(parent, "kmediabridge-${platform.id}-")
                    }
                secureDirectory(directory)
                directory.toFile().deleteOnExit()
                manifest.libraries.forEach { library ->
                    requireSimpleName(library.name)
                    val stream = classLoader.getResourceAsStream("$prefix/${library.name}")
                        ?: reject("A native library listed by the embedded manifest is missing.")
                    val target = directory.resolve(library.name)
                    stream.use { Files.copy(it, target) }
                    secureFile(target)
                    target.toFile().deleteOnExit()
                }
                return directory
            }
        }

        class Replacement(
            private val platform: DesktopPlatform,
            private val directory: Path,
        ) : RuntimeSource {
            override fun readManifest(): NativePayloadManifest {
                val manifestPath = directory.toAbsolutePath().normalize().resolve(MANIFEST_NAME)
                val stream =
                    try {
                        Files.newInputStream(manifestPath)
                    } catch (error: Exception) {
                        throw MediaBridgeException(
                            MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME,
                            "The replacement runtime has no readable manifest.properties.",
                            error,
                        )
                    }
                return stream.use(NativePayloadManifest::read)
            }

            override fun materialize(manifest: NativePayloadManifest): Path = directory
        }
    }

    private fun requireSimpleName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
            reject("A native manifest contains an unsafe library name.")
        }
    }

    private fun secureDirectory(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows has no POSIX permission view.
        }
    }

    private fun secureFile(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows has no POSIX permission view.
        }
    }

    internal fun reject(message: String): Nothing =
        throw MediaBridgeException(MediaBridgeErrorCode.NON_COMPLIANT_NATIVE_RUNTIME, message)
}

private enum class LibraryRole {
    DEPENDENCY,
    BRIDGE,
}

private data class NativeLibraryEntry(
    val name: String,
    val sha256: String,
    val role: LibraryRole,
)

private data class NativePayloadManifest(
    val platform: String,
    val abiVersion: Int,
    val ffmpegVersion: String,
    val ffmpegLicenseSpdx: String,
    val ffmpegReportedLicense: String,
    val sourceOfferUrl: String,
    val sourceSha256: String,
    val buildRecipeUrl: String,
    val buildRecipeRevision: String,
    val exactCorrespondingSourceAvailable: Boolean,
    val dynamicLinkingVerified: Boolean,
    val libraries: List<NativeLibraryEntry>,
) {
    companion object {
        fun read(stream: InputStream): NativePayloadManifest {
            val properties = Properties().apply { load(stream) }
            fun required(name: String): String =
                properties.getProperty(name)?.takeIf(String::isNotBlank)
                    ?: DesktopRuntimeLoader.run { reject("The native manifest is missing $name.") }

            if (required("schemaVersion") != "1") {
                DesktopRuntimeLoader.run { reject("The native manifest schema is unsupported.") }
            }
            val count = required("library.count").toIntOrNull()
                ?: DesktopRuntimeLoader.run { reject("The native manifest has an invalid library count.") }
            val libraries =
                (0 until count).map { index ->
                    val role =
                        runCatching { LibraryRole.valueOf(required("library.$index.role")) }
                            .getOrElse { DesktopRuntimeLoader.run { reject("The native manifest has an invalid library role.") } }
                    NativeLibraryEntry(
                        name = required("library.$index.name"),
                        sha256 = required("library.$index.sha256"),
                        role = role,
                    )
                }
            if (libraries.count { it.role == LibraryRole.BRIDGE } != 1) {
                DesktopRuntimeLoader.run { reject("The native manifest must contain one bridge library.") }
            }
            if (libraries.map(NativeLibraryEntry::name).distinct().size != libraries.size) {
                DesktopRuntimeLoader.run { reject("The native manifest contains duplicate library names.") }
            }
            return NativePayloadManifest(
                platform = required("platform"),
                abiVersion = required("abiVersion").toIntOrNull()
                    ?: DesktopRuntimeLoader.run { reject("The native manifest ABI is invalid.") },
                ffmpegVersion = required("ffmpegVersion"),
                ffmpegLicenseSpdx = required("ffmpegLicenseSpdx"),
                ffmpegReportedLicense = required("ffmpegReportedLicense"),
                sourceOfferUrl = required("sourceOfferUrl"),
                sourceSha256 = required("sourceSha256"),
                buildRecipeUrl = required("buildRecipeUrl"),
                buildRecipeRevision = required("buildRecipeRevision"),
                exactCorrespondingSourceAvailable = required("exactCorrespondingSourceAvailable").toBooleanStrict(),
                dynamicLinkingVerified = required("dynamicLinkingVerified").toBooleanStrict(),
                libraries = libraries,
            )
        }
    }
}

private data class DesktopPlatform(val id: String) {
    companion object {
        fun detect(): DesktopPlatform {
            val osName = System.getProperty("os.name", "").lowercase()
            val architecture = System.getProperty("os.arch", "").lowercase()
            val os =
                when {
                    "mac" in osName || "darwin" in osName -> "macos"
                    "win" in osName -> "windows"
                    "linux" in osName -> "linux"
                    else -> DesktopRuntimeLoader.run { reject("This desktop operating system has no bundled FFmpeg payload.") }
                }
            val arch =
                when (architecture) {
                    "aarch64", "arm64" -> "aarch64"
                    "amd64", "x86_64", "x64" -> "x86_64"
                    else -> DesktopRuntimeLoader.run { reject("This desktop architecture has no bundled FFmpeg payload.") }
                }
            return DesktopPlatform("$os-$arch")
        }
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
