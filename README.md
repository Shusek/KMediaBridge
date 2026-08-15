# KMediaBridge

KMediaBridge is an optional Kotlin Multiplatform remux and tone-map backend. Since `0.5.0`, it is a **client of** [KMediaFfmpegRuntime](https://github.com/Shusek/KMediaFfmpegRuntime): it no longer builds or distributes a second FFmpeg.

## Dependency

Both the platform client and the exact shared runtime are transitive:

```kotlin
commonMain.dependencies {
    implementation("io.github.shusek:kmedia-bridge-ffmpeg:0.5.0-rc.11")
}
```

The lower-level engine-neutral contracts are available as
`io.github.shusek:kmedia-bridge-api:0.5.0-rc.11`. Do not add a native client or
`runtimeOnly` dependency manually.

```kotlin
val driver = BundledFfmpegNativeDriver.load()
val bridge = FfmpegMediaBridge.create(driver)
```

Android uses `AndroidFfmpegNativeDriver.load()` and supports only `arm64-v8a` and `armeabi-v7a` with `minSdk 23`.

## Supported native targets

| Platform | Architectures |
|---|---|
| Android | ARM64, ARMv7 |
| Linux | x86_64, ARM64 |
| Windows | x86_64 |
| macOS | ARM64 |

KMediaBridge is not an iOS backend. Its common API may be consumed by iOS source sets, but no KMediaBridge native client is published there.

The macOS and Windows desktop clients enable their full conversion path only
when the selected shared-runtime manifest authenticates it. That path can
tone-map explicit PQ/HLG to SDR, compose selected text subtitles, and convert
legacy SDR AVI/ASF and WMA-family media to platform-compatible AVC/AAC
fragmented MP4. Older shared runtimes keep Windows in remux-only mode.

Encoder selection is hardware-first and fail-safe. macOS first opens
VideoToolbox without software encoding, while Windows first opens Media
Foundation with `hw_encoding=1` and an NV12 input, then tries its other audited
hardware candidates. Only after every hardware attempt fails may the same
platform encoder allow software output or use an available LGPL-compatible
software encoder. Android HDR-to-SDR output uses MediaCodec, except for the
existing NVIDIA compatibility override that may select the known-good Android
software encoder to avoid a device-specific MediaCodec flush failure. Linux
remains remux-only unless a future authenticated runtime explicitly enables a
complete transcode capability.

## One process, one runtime

Before loading the bridge, KMediaBridge initializes `KMediaFfmpegRuntime` and
verifies the exact runtime ID recorded when the client was built. A process
that already selected another runtime ID fails with a controlled compatibility
error. The bridge artifact contains only `libkmediabridge`. The exact FFmpeg
runtime dependency owns the six FFmpeg libraries and transitively selects the
separate exact `KMediaAssRuntime`, which owns libass, FreeType, FriBidi and
HarfBuzz once for the process.

KMediaPlayer may therefore include both its MPV and KMediaBridge adapters. Both clients bind to the same prefixed dynamic-library graph.

## Licensing

The Kotlin API/backend keep `LicenseRef-KMediaBridge-Internal`. Existing native bridge code and compliance tooling keep their stated `LGPL-2.1-or-later` notices. The shared FFmpeg distribution, corresponding source, patches, SBOM, signatures and relinking instructions are provided by KMediaFfmpegRuntime. Separating that component does not relicense the independent KMediaBridge code.

Public availability is not a grant beyond the license attached to each artifact. This project is not legal advice.

## Verification

```shell
./gradlew check complianceCheck
```

Hosted Actions build and inspect clients and package graphs. Accelerated Android ARM emulator/device tests are recorded as a release attestation and run locally, because the hosted matrix does not provide the required nested virtualization.

See [architecture](docs/ARCHITECTURE.md), [compliance](docs/COMPLIANCE.md), [releasing](docs/RELEASING.md), and [client rebuilding](docs/RELINKING.md).
