# Native bridge

This directory contains the stable C ABI used by Kotlin platform drivers. It
does not contain or download a prebuilt third-party FFmpeg binary.

`build-ffmpeg-unix.sh` builds the pinned upstream source with an explicit LGPL
configuration, links the bridge dynamically, copies the exact source archive
beside the result, and interrogates the finished library for its actual license
and configure arguments. The staged result also includes the bridge source,
generated configuration, compiler identity, relinking guide, and SHA-256
inventory.

The generated `build/native-dist` directory is intentionally ignored. A native
payload is eligible for publication only after it has been transformed into a
platform artifact with an embedded compliance manifest and declared in
`compliance/ffmpeg/manifest.json`.

The current C ABI supports probing and lossless fragmented-MP4 remuxing. It
does not decode, tone-map, or encode video and therefore does not require x264
or x265.
