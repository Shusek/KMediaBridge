# KMediaBridge

KMediaBridge is a mixed-license Kotlin Multiplatform media bridge: its Kotlin
core is internal-use, while its separately replaceable bundled FFmpeg runtime
remains LGPL-2.1-or-later. It probes local media and losslessly remuxes
Matroska/WebM, MP4/fMP4, and MPEG-TS into a streamed fragmented-MP4/CMAF
output. The macOS desktop flavor can additionally decode SDR video, compose a
selected text subtitle track with libass, and encode tagged BT.709 CMAF through
VideoToolbox.

There is no `ffmpeg`/`ffprobe` process and no system FFmpeg prerequisite. The
desktop artifact contains dynamically linked native libraries built from the
pinned FFmpeg source with GPL and nonfree components disabled.

## Artifacts

Starting with 0.4.0, the Kotlin API and backend are internal-use artifacts.
All three coordinates are publicly available from Maven Central. Public
availability does not grant rights beyond the license identified by each
artifact: the API and backend remain internal-use, while the independently
replaceable native runtime remains LGPL:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.shusek:kmedia-bridge-api:0.4.1")
            implementation("io.github.shusek:kmedia-bridge-ffmpeg:0.4.1")
        }
        jvmMain.dependencies {
            // Optional and intentionally separate from the API.
            runtimeOnly("io.github.shusek:kmedia-bridge-ffmpeg-runtime-desktop:0.4.1")
        }
    }
}
```

The LGPL runtime is additionally mirrored to
`https://shusek.github.io/KMediaBridge/maven` together with historical 0.3.0
artifacts. Consumers of current releases need only `mavenCentral()`.

- `kmedia-bridge-api` contains engine-neutral KMP models and session contracts.
- `kmedia-bridge-ffmpeg` contains the common backend plus the desktop JVM
  loader, compliance checks, typed probe parser, and CMAF fragment stream.
- `kmedia-bridge-ffmpeg-runtime-desktop` contains FFmpeg 8.1.2 and the C bridge
  for macOS arm64/x64, Linux x64, and Windows x64.

Create the desktop backend with:

```kotlin
val driver = BundledFfmpegNativeDriver.load()
val bridge = FfmpegMediaBridge.create(driver)
```

Runtime selection is explicit and typed. The separate runtime artifact is the
deterministic default; applications may instead use a compatible runtime
installed outside the application or configure a preference order:

```kotlin
val driver = BundledFfmpegNativeDriver.load(
    runtimeSelection = FfmpegRuntimeSelection(
        policy = FfmpegRuntimePolicy.PREFER_EXTERNAL,
        externalRuntimeDirectory = Path.of("/opt/kmediabridge/ffmpeg-runtime"),
    ),
)
```

Available policies are `BUNDLED_ONLY`, `EXTERNAL_ONLY`, `PREFER_BUNDLED`, and
`PREFER_EXTERNAL`. Preference fallback occurs only when the preferred source
has no manifest. A present runtime with a bad hash, incompatible ABI, or an
identity that differs from its manifest is rejected and never silently
bypassed.

An external runtime directory must be a KMediaBridge-compatible dynamic
library bundle with `manifest.properties`; it is not a system `ffmpeg`
executable and not an arbitrary set of distro/Homebrew DLLs or dylibs. It may,
however, use a caller-selected FFmpeg build whose effective license is GPL.
KMediaBridge verifies the bridge ABI, files, hashes, and reported runtime
identity, but it does not certify a caller-provided payload for redistribution.
`driver.runtimeInfo.origin`, `complianceScope`, `ffmpegLicenseSpdx`, and
`configureArguments` report what was actually selected.

Capability discovery does not have to load native code. Use
`DesktopFfmpegRuntimeInspector.inspect()` to validate only the selected
manifest; the libraries are extracted and loaded only if the player actually
selects this backend.

For a desktop player that consumes HLS, the JVM adapter owns the bounded
loopback origin as well:

```kotlin
val playback = BundledFfmpegHlsPlaybackBackend.start(
    FfmpegHlsPlaybackRequest(
        input = MediaInput("/media/movie.mkv", MediaInputKind.FILE),
        selectedAudioTrackId = 2,
        // Optional on a subtitle-capable runtime; text is rendered into SDR video.
        selectedSubtitleTrackId = 3,
    ),
)
player.open(playback.source.playlistUrl)
// Later:
playback.close()
```

`playback.source.outputInfo` reports the selected tracks and the requested
video/audio/subtitle handling. `copiedHdrSignal` reports only that a strict
HEVC Main 10 HDR10/HDR10+/HLG signal was copied into CMAF; it deliberately does
not claim that a decoder, surface, display, or physical output is HDR.

`BundledFfmpegNativeDriver.load()` extracts the matching libraries into a
private temporary directory, verifies every SHA-256, loads dependencies in a
fixed order, and asks the running library for its ABI, version, license, and
configure line before it accepts media.

The historical 0.3.0 API, backend, and runtime remain at the public Pages
repository under the LGPL terms conveyed with that release. New internal-use
core artifacts are published to Maven Central but are never added to the
runtime-only Pages repository.

## What the bridge does

- probes local, unencrypted files without logging their paths;
- exposes typed video, audio and subtitle tracks and explicit track selection;
- streams fMP4 through a native callback with backpressure;
- serves that stream as bounded-memory CMAF/HLS on loopback for JVM players;
- copies compatible compressed video/audio without re-encoding the picture;
- restarts from a preceding keyframe after seek;
- preserves codec color and HDR metadata that FFmpeg can carry through this
  remux path.
- on macOS, optionally burns selected ASS/SSA/SRT/WebVTT/mov_text into an SDR
  BT.709 AVC output using libass plus VideoToolbox.

It does not burn bitmap subtitles, compose subtitles into HDR/HLG/Dolby Vision,
tone-map, transcode audio, convert Dolby Vision profile 7, handle
remote/DRM/live input, or certify Dolby Vision. HDR subtitle requests are
rejected rather than silently flattened or mislabeled. Linux and Windows keep
the remux-only flavor until their reviewed native encoders are enabled.
KMediaPlayer still chooses and confirms the actual AVFoundation/Media
Foundation/GStreamer/rendering path after receiving the fMP4 stream. Android
and Apple Kotlin/Native payloads remain separate future artifacts; the bundled
runtime described here is for desktop JVM.

The FFmpeg dylib/SONAME/DLL names use a private `-kmb` suffix. Native loading is
local rather than process-global; Darwin additionally uses first-image symbol
lookup, while ELF builds require private `LIBAV*_KMB_*` symbol versions. This
lets an in-process libVLC backend coexist without binding to KMediaBridge's
FFmpeg (or the reverse). Loading both still consumes memory, so KMediaPlayer
selects one fallback for a request and the manifest-only inspection avoids eager
loading.

## Mixed license boundary and external runtimes

The first-party Kotlin API and backend are covered by the KMediaBridge Internal
Use Notice and Limited License. The native bridge, runtime artifact, rebuild
and relinking material, and compliance tooling are LGPL-2.1-or-later. Native
libraries are dynamically linked and never committed to Git; release CI builds
them from the exact pinned source. Each stable release publishes the matching
source archive, recipes, configure lines, compiler identity, native hashes,
SBOM, platform bundles, and relinking instructions beside the public runtime
artifact. See [LICENSE](LICENSE) for the exact scope map.

A user may provide rebuilt libraries without changing KMediaBridge:

```kotlin
val driver = BundledFfmpegNativeDriver.load(
    runtimeSelection = FfmpegRuntimeSelection.fromExternalDirectory(
        Path.of("/path/to/rebuilt/runtime"),
    ),
)
```

The external directory supplies its own `manifest.properties`. Technical
checks remain fail-closed, but the native-runtime LGPL publication gate does
not reject a GPL runtime that the caller supplied and KMediaBridge did not
convey. Such a runtime has `complianceScope == CALLER_PROVIDED`; the application
author or operator is responsible for determining whether and how the
resulting combination may be distributed. See [Compliance](docs/COMPLIANCE.md),
[Relinking](docs/RELINKING.md), and [Architecture](docs/ARCHITECTURE.md).

Run the complete source and publication gate with:

```shell
./gradlew check complianceCheck
```

This repository is not legal advice. Redistributors remain responsible for
patent, export, store, and codec-licensing obligations in their jurisdictions.
