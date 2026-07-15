# Rebuilding and replacing native libraries

Official native payloads use a versioned C ABI and dynamically linked FFmpeg
libraries. No application code invokes an FFmpeg executable.

To rebuild the Unix runtime from its corresponding source:

```shell
./native/build-ffmpeg-unix.sh build/native-work build/native-dist
```

The staged directory contains the exact FFmpeg source archive, bridge source,
build recipe, generated FFmpeg configuration, compiler identity, license
texts, notices, runtime inspection, and a SHA-256 inventory. Verify the
inventory before replacing any file.

A compatible replacement must retain C ABI version 3 and the platform library
names. Replace `libavformat`, `libavcodec`, and `libavutil` together, then
rebuild `libkmediabridge` against those exact headers. Run
`scripts/inspect_native_runtime.py` again; a GPL/nonfree, statically linked, or
otherwise undocumented runtime is rejected.

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

The manifest lists every library name/hash, ABI, reported FFmpeg identity,
source offer, and immutable recipe revision. The loader verifies the replacement
directory rather than comparing it to hashes from the official bundle.
`PREFER_EXTERNAL` and `PREFER_BUNDLED` provide a controlled fallback order,
but a present manifest that fails validation always stops loading instead of
silently selecting the other source.
