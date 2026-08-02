# Native client

`build-client.py` is the canonical builder. It compiles the thin KMediaBridge library against an immutable KMediaFfmpegRuntime SDK and emits one client library, a manifest and a consumable SDK.

It supports Android ARM64/ARMv7, Linux x86_64/ARM64, Windows x86_64 and macOS ARM64. It never builds or packages FFmpeg or the subtitle stack.

ABI 4 supports probing audio/video/subtitle tracks, authenticated runtime
features, lossless selected-track fragmented-MP4 remuxing, optional SDR text
subtitle composition, and an AVFoundation compatibility stream. On macOS the
client uses the immutable shared-runtime SDK to decode supported legacy SDR
AVI/ASF and WMA inputs, normalize the picture to limited BT.709, encode AVC
with VideoToolbox and AAC, and stream fragmented MP4 to AVFoundation.
