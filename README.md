# KMediaBridge

KMediaBridge is an engine-neutral Kotlin Multiplatform API for probing media
and bridging containers into CMAF/fMP4. FFmpeg support is optional, hidden
behind a narrow native-driver SPI, and rejected at runtime unless its LGPL
compliance evidence is complete.

The public API never launches `ffmpeg` or `ffprobe` executables. Desktop,
Android, and Apple payloads are designed as dynamically linked native
libraries (`.dylib`, `.dll`, `.so`, or frameworks) behind the same Kotlin API.

## Artifacts

```kotlin
repositories {
    maven("https://shusek.github.io/KMediaBridge/maven")
}

dependencies {
    implementation("io.github.shusek:kmedia-bridge-api:0.1.0")

    // Optional: add only when the FFmpeg backend is wanted.
    implementation("io.github.shusek:kmedia-bridge-ffmpeg:0.1.0")
}
```

- `kmedia-bridge-api` contains engine-neutral KMP models, selection, probing,
  and fragment-session contracts.
- `kmedia-bridge-ffmpeg` contains the FFmpeg driver SPI, strict runtime
  compliance verification, and backend orchestration.

Version `0.1.0` establishes and publishes the Kotlin contract and native ABI.
It deliberately does not publish native FFmpeg binaries until each payload
passes the source-offer, license, reproducibility, and hardware tests. This is
a fail-closed release boundary, not an implicit system-FFmpeg fallback.

## Platforms

The Kotlin artifacts publish metadata for Android, JVM, iOS, macOS,
Linux, Windows (MinGW), and Wasm. Native runtime payloads are released
independently only after their platform matrix is green.

Android callers should normally prefer Media3 for Matroska/HDR playback and
install this backend only for unsupported conversion paths. Windows callers
should prefer Media Foundation and install the bridge for deterministic
fallbacks. macOS JVM is the first planned native runtime because AVFoundation
does not accept Matroska directly.

## Compliance model

KMediaBridge itself is LGPL-2.1-or-later. A native FFmpeg runtime is accepted
only when all of the following are true:

- FFmpeg reports LGPL and is declared as LGPL-2.1-or-later or LGPL-3.0-or-later;
- the build explicitly uses `--disable-gpl` and `--disable-nonfree`;
- no forbidden GPL/nonfree component is enabled;
- the exact corresponding source, immutable build recipe, build arguments,
  component licenses, and SHA-256 hashes are available;
- the binary carries an embedded compliance manifest matching its release.

Run the complete local gate with:

```shell
./gradlew check complianceCheck
```

See [Compliance](docs/COMPLIANCE.md), [Architecture](docs/ARCHITECTURE.md), and
[Releasing](docs/RELEASING.md).

## License

KMediaBridge is licensed under LGPL-2.1-or-later. This is not legal advice;
redistributors remain responsible for copyright, patent, export, store, and
codec-licensing obligations in their jurisdictions.
