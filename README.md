# KMediaBridge

KMediaBridge is an optional Kotlin Multiplatform remux and tone-map backend. Since `0.5.0`, it is a **client of** [KMediaFfmpegRuntime](https://github.com/Shusek/KMediaFfmpegRuntime): it no longer builds or distributes a second FFmpeg.

## Dependency

Both the platform client and the exact shared runtime are transitive:

```kotlin
commonMain.dependencies {
    implementation("io.github.shusek:kmedia-bridge-ffmpeg:0.5.0-rc.1")
}
```

The lower-level engine-neutral contracts are available as `io.github.shusek:kmedia-bridge-api:0.5.0-rc.1`. Do not add a native client or `runtimeOnly` dependency manually.

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

## One process, one runtime

Before loading the bridge, KMediaBridge initializes `KMediaFfmpegRuntime` and verifies the exact runtime ID recorded when the client was built. A process that already selected another runtime ID fails with a controlled compatibility error. The bridge artifact contains only `libkmediabridge`; FFmpeg, libass, FreeType, FriBidi and HarfBuzz come from the shared runtime.

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
