# Architecture

`kmedia-bridge-api` contains engine-neutral contracts. `kmedia-bridge-ffmpeg` contains the optional Kotlin backend and transitively selects one platform client:

```text
kmedia-bridge-ffmpeg
├── kmedia-bridge-client-android  ─┐
├── kmedia-bridge-client-desktop  ─┼─ exact KMediaFfmpegRuntime 0.1.0-rc.9
└── kmedia-bridge-api             ─┘
```

The Android client AAR contains one `libkmediabridge.so` for each supported ARM ABI. The desktop client JAR contains one bridge library for Linux x86_64/ARM64, Windows x86_64 and macOS ARM64. It contains no FFmpeg-family libraries.

The shared runtime is initialized first. Its process-global runtime ID is compared with `sharedRuntimeId` in the client manifest before native client loading. The loader verifies the platform, bridge ABI, closed library inventory and SHA-256. Shared libraries use prefixed names supplied only by KMediaFfmpegRuntime.

Linux clients use `$ORIGIN`, macOS uses `@rpath`, and Windows loads the shared runtime DLLs before the bridge DLL. Client and runtime SDKs are immutable release artifacts; the build accepts an SDK directory and never fetches a Git submodule.

## Platform strategy

- **macOS JVM:** the thin arm64 client feeds AVFoundation copied CMAF/fMP4 or
  converts legacy SDR media to AVC through VideoToolbox and AAC through the
  shared runtime. An explicitly selected text subtitle may be composed by
  libass during that conversion.
- **Windows JVM:** the thin x64 client keeps Media Foundation as the primary
  decoder and D3D renderer. When the shared runtime advertises the full desktop
  feature set, the bridge can tone-map explicit PQ/HLG to SDR or convert legacy
  SDR media and selected text subtitles to Media Foundation-compatible AVC/AAC
  fragmented MP4.
- **Android:** Media3 remains primary; optional `.so` only for gaps.
- **iOS:** dynamic framework/XCFramework with Kotlin/Native interop.
- **Linux JVM:** thin x64/ARM64 clients keep system GStreamer as the confirmed
  HDR display route and use this bridge only as an optional container fallback.
- **Wasm:** the API is available, but no full FFmpeg.wasm payload is promised.

## Current native ABI

ABI version 4 exposes runtime identity, an authenticated feature declaration, a
typed probe JSON document (including audio and subtitle selection metadata), a
file remux operation, a track-selecting callback-based fragmented-MP4 stream,
an optional SDR subtitle composition operation, and an AVC/AAC compatibility
stream. The callback supports backpressure and cancellation. Remux-only clients
never decode the picture. Full desktop clients can use the shared runtime to
decode legacy AVI/ASF video and WMA1/2/Pro/Lossless/Voice audio, compose text
subtitles in libass, normalize to limited BT.709, encode AVC with VideoToolbox
on macOS or Media Foundation on Windows plus AAC, and deliver CMAF/fMP4 to the
platform player. PQ, HLG, BT.2020, Dolby Vision, and HDR10+ inputs are rejected
by that SDR compatibility operation and must use the explicit color pipeline.
