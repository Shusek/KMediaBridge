# Native client

`build-client.py` is the canonical builder. It compiles the thin KMediaBridge library against an immutable KMediaFfmpegRuntime SDK and emits one client library, a manifest and a consumable SDK.

It supports Android ARM64/ARMv7, Linux x86_64/ARM64, Windows x86_64 and macOS ARM64. It never builds or packages FFmpeg or the subtitle stack.
