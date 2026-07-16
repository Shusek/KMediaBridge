# Native bridge

This directory contains the stable C ABI and deterministic build recipes used
by Kotlin platform drivers. It does not commit a prebuilt FFmpeg binary.

`build-ffmpeg-unix.sh` and `build-ffmpeg-windows-x64.sh` build the pinned source with an explicit LGPL
configuration, links the bridge dynamically, copies the exact source archive
beside the result, and interrogates the finished library for its actual license
and configure arguments. The staged result also includes the bridge source,
generated configuration, compiler identity, relinking guide, and SHA-256
inventory.

Generated native directories are intentionally ignored. Release CI transforms
each inspected result into a classpath payload, embeds per-platform and
aggregate manifests, and publishes binaries only from an immutable tag.

ABI 4 supports probing audio/video/subtitle tracks, an authenticated runtime
feature declaration, lossless selected-track fragmented-MP4 remuxing to a path
or a backpressured callback, and an optional SDR text subtitle operation. Build
the latter on macOS with:

```shell
./native/build-ffmpeg-unix.sh build/native-work build/native-dist subtitle-sdr
```

That profile builds the pinned libass stack, decodes supported SDR formats,
normalizes output to limited BT.709, and uses VideoToolbox for AVC. The default
profile remains remux-only. Neither profile requires x264 or x265.
