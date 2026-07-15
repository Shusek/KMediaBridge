# Architecture

## Boundary

```text
application / KMediaPlayer
        |
        v
kmedia-bridge-api (Kotlin Multiplatform)
        |
        v
kmedia-bridge-ffmpeg (compliance gate + orchestration)
        |
        v
versioned C ABI: kmedia_bridge.h
        |
        v
dynamically linked LGPL FFmpeg libraries

kmedia-bridge-ffmpeg-runtime-desktop (runtimeOnly resources)
        |
        +-- macOS arm64/x64 dylibs
        +-- Linux x64 shared objects
        +-- Windows x64 DLLs
```

The Kotlin API is engine-neutral. FFmpeg types, pointers, command-line syntax,
and ABI details never appear in application-facing contracts.

## Runtime rules

- No application code invokes an FFmpeg executable.
- The native runtime exposes a small versioned C ABI.
- Desktop JVM calls that ABI through JNA; it never invokes a program.
- Native libraries are dynamically linked to keep the LGPL replacement and
  relinking boundary explicit.
- Input locators are never included in diagnostic strings because URLs may
  contain credentials.
- The runtime refuses non-LGPL builds before opening media.
- Encrypted media and DRM are outside the conversion bridge.

## Platform strategy

- **macOS JVM:** bundled arm64/x64 `.dylib`; AVFoundation receives copied CMAF/fMP4.
- **Windows JVM:** bundled x64 `.dll`; Media Foundation remains the primary decoder
  and D3D renderer.
- **Android:** Media3 remains primary; optional `.so` only for gaps.
- **iOS:** dynamic framework/XCFramework with Kotlin/Native interop.
- **Linux JVM:** bundled x64 `.so`; system GStreamer remains the confirmed HDR
  display route and this bridge is an optional container fallback.
- **Wasm:** the API is available, but no full FFmpeg.wasm payload is promised.

## Current native ABI

ABI version 2 exposes runtime identity, a typed probe JSON document, a file
remux operation, and a callback-based fragmented-MP4 stream. The callback
supports backpressure and cancellation. The remuxer copies supported
audio/video packets and never re-encodes the video picture.
