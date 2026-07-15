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
```

The Kotlin API is engine-neutral. FFmpeg types, pointers, command-line syntax,
and ABI details never appear in application-facing contracts.

## Runtime rules

- No application code invokes an FFmpeg executable.
- The native runtime exposes a small versioned C ABI.
- Native libraries are dynamically linked to keep the LGPL replacement and
  relinking boundary explicit.
- Input locators are never included in diagnostic strings because URLs may
  contain credentials.
- The runtime refuses non-LGPL builds before opening media.
- Encrypted media and DRM are outside the conversion bridge.

## Platform strategy

- **macOS JVM:** native `.dylib`; AVFoundation receives copied CMAF/fMP4.
- **Windows JVM:** native `.dll`; Media Foundation remains the primary decoder
  and D3D renderer.
- **Android:** Media3 remains primary; optional `.so` only for gaps.
- **iOS:** dynamic framework/XCFramework with Kotlin/Native interop.
- **Linux:** system GStreamer remains the confirmed HDR route; this bridge is
  optional.
- **Wasm:** the API is available, but no full FFmpeg.wasm payload is promised.

## Current native ABI

ABI version 1 exposes runtime identity, a typed probe JSON document, and a
lossless fragmented-MP4 remux operation. The remuxer copies supported
audio/video packets and never re-encodes the video picture.
