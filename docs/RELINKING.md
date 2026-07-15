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
`scripts/inspect_native_runtime.py` again when preparing an LGPL payload for
redistribution by KMediaBridge; that publication path rejects GPL/nonfree,
statically linked, or otherwise undocumented builds.

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

The manifest always lists every library name/hash, ABI, and reported FFmpeg
identity. Source-offer and immutable-recipe evidence are mandatory for a
KMediaBridge-distributed payload and optional for a caller-provided external
runtime. The loader verifies the external directory rather than comparing it to
hashes from the official bundle.
`PREFER_EXTERNAL` and `PREFER_BUNDLED` provide a controlled fallback order,
but a present manifest that fails validation always stops loading instead of
silently selecting the other source.

An external runtime may report GPL rather than LGPL. In that case the loader
accepts it as `CALLER_PROVIDED` after technical verification and exposes its
reported license/configuration in `FfmpegRuntimeInfo`. This only separates it
from KMediaBridge's publication compliance gate; it does not grant permission
to distribute an application combined with that runtime.
