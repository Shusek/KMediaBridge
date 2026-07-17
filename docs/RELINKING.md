# Rebuilding and replacing native libraries

Official native payloads use a versioned C ABI and dynamically linked FFmpeg
libraries. No application code invokes an FFmpeg executable.

To rebuild the Unix runtime from its corresponding source:

```shell
./native/build-ffmpeg-unix.sh build/native-work build/native-dist
```

For the macOS SDR subtitle flavor, use the reviewed full profile:

```shell
./native/build-ffmpeg-unix.sh build/native-work build/native-dist subtitle-sdr
```

For Android, use the pinned NDK and select the ABIs to rebuild:

```shell
./native/build-ffmpeg-android.sh \
  "$ANDROID_NDK_HOME" \
  build/native-work-android \
  build/native-dist-android \
  arm64-v8a,armeabi-v7a,x86_64
```

The staged directory contains the exact FFmpeg source archive, bridge source,
build recipe, generated FFmpeg configuration, compiler identity, license
texts, notices, runtime inspection, and a SHA-256 inventory. Verify the
inventory before replacing any file.

A compatible replacement must retain C ABI version 4 and the platform library
names. Replace `libavformat-kmb`, `libavcodec-kmb`, and `libavutil-kmb` together;
the subtitle flavor additionally replaces `libavfilter-kmb` and
`libswscale-kmb`. Then rebuild `libkmediabridge` against those exact headers. Run
`scripts/inspect_native_runtime.py` again when preparing an LGPL payload for
redistribution by KMediaBridge; that publication path rejects GPL/nonfree,
statically linked, or otherwise undocumented builds.

The `-kmb` suffix is part of the isolation boundary. It prevents KMediaBridge
and an in-process player such as libVLC from binding to one another's FFmpeg
libraries. Replacement runtimes must preserve it in their SONAME,
install-name, DLL, and import-library identities. ELF replacements must also
preserve the private `LIBAV*_KMB_*` symbol-version namespaces; the runtime
inspection rejects a generic FFmpeg symbol namespace.

Platform signing and packaging happen after this reproducible build step.
Redistributors must provide a practical replacement/relinking route appropriate
to their signing and app-store model before shipping a native payload.

For desktop JVM, place the rebuilt libraries and a matching
`manifest.properties` in one directory and select it explicitly:

```kotlin
BundledFfmpegNativeDriver.load(
    runtimeSelection = FfmpegRuntimeSelection.fromExternalDirectory(Path.of("/replacement")),
)
```

For Android, place `android-runtime.properties` at the replacement root, put
each five-library set in a child directory named for its ABI, and select it
before the first native call:

```kotlin
AndroidFfmpegNativeDriver.load(
    AndroidFfmpegRuntimeSelection.ExternalDirectory(File("/replacement")),
)
```

The manifest always lists every library name/hash, ABI, and reported FFmpeg
identity. Source-offer and immutable-recipe evidence are mandatory for a
KMediaBridge-distributed payload and optional for a caller-provided external
runtime. The loader verifies the external directory rather than comparing it to
hashes from the official bundle.
Subtitle-capable manifests also list the exact FreeType, FriBidi, HarfBuzz,
libunibreak, and libass sources incorporated into `libavfilter-kmb`.
`PREFER_EXTERNAL` and `PREFER_BUNDLED` provide a controlled fallback order,
but a present manifest that fails validation always stops loading instead of
silently selecting the other source.

An external runtime may report GPL rather than LGPL. In that case the loader
accepts it as `CALLER_PROVIDED` after technical verification and exposes its
reported license/configuration in `FfmpegRuntimeInfo`. This only separates it
from KMediaBridge's publication compliance gate; it does not grant permission
to distribute an application combined with that runtime.
