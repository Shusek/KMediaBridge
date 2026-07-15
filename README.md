# KMediaBridge

KMediaBridge is a Kotlin Multiplatform media bridge with an optional, bundled
FFmpeg runtime. It probes local media and losslessly remuxes Matroska/WebM,
MP4/fMP4, and MPEG-TS into a streamed fragmented-MP4/CMAF output.

There is no `ffmpeg`/`ffprobe` process and no system FFmpeg prerequisite. The
desktop artifact contains dynamically linked native libraries built from the
pinned FFmpeg source with GPL and nonfree components disabled.

## Artifacts

```kotlin
repositories {
    maven("https://shusek.github.io/KMediaBridge/maven")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.shusek:kmedia-bridge-api:0.3.0")
            implementation("io.github.shusek:kmedia-bridge-ffmpeg:0.3.0")
        }
        jvmMain.dependencies {
            // Optional and intentionally separate from the API.
            runtimeOnly("io.github.shusek:kmedia-bridge-ffmpeg-runtime-desktop:0.3.0")
        }
    }
}
```

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

For a desktop player that consumes HLS, the JVM adapter owns the bounded
loopback origin as well:

```kotlin
val playback = BundledFfmpegHlsPlaybackBackend.start(
    FfmpegHlsPlaybackRequest(
        input = MediaInput("/media/movie.mkv", MediaInputKind.FILE),
        selectedAudioTrackId = 2,
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

## What 0.3.0 does

- probes local, unencrypted files without logging their paths;
- exposes typed video, audio and subtitle tracks and explicit track selection;
- streams fMP4 through a native callback with backpressure;
- serves that stream as bounded-memory CMAF/HLS on loopback for JVM players;
- copies compatible compressed video/audio without re-encoding the picture;
- restarts from a preceding keyframe after seek;
- preserves codec color and HDR metadata that FFmpeg can carry through this
  remux path.

It does not itself render HDR, tone-map or transcode video, transcode audio,
burn subtitles, convert Dolby Vision profile 7, handle remote/DRM/live input,
or certify Dolby Vision. Unsupported requests fail with a typed error rather
than silently producing unmanaged color. KMediaPlayer still chooses and
confirms the actual AVFoundation/Media Foundation/GStreamer/rendering path
after receiving the fMP4 stream. Android and Apple Kotlin/Native payloads
remain separate future artifacts; the bundled 0.3.0 runtime is for desktop JVM.

## LGPL and replacement

KMediaBridge and the selected FFmpeg build are LGPL-2.1-or-later. Native
libraries are dynamically linked and never committed to Git; release CI builds
them from the exact pinned source. Each stable release publishes the matching
source archive, recipes, configure lines, compiler identity, native hashes,
SBOM, platform bundles, and relinking instructions beside the Maven artifact.

A user may provide rebuilt libraries without changing KMediaBridge:

```kotlin
val driver = BundledFfmpegNativeDriver.load(
    replacementDirectory = Path.of("/path/to/rebuilt/runtime"),
)
```

The replacement directory supplies its own `manifest.properties`; the same
fail-closed runtime checks apply. See [Compliance](docs/COMPLIANCE.md),
[Relinking](docs/RELINKING.md), and [Architecture](docs/ARCHITECTURE.md).

Run the complete source and publication gate with:

```shell
./gradlew check complianceCheck
```

This repository is not legal advice. Redistributors remain responsible for
patent, export, store, and codec-licensing obligations in their jurisdictions.
