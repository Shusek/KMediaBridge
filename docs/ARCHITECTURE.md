# Architecture

`kmedia-bridge-api` contains engine-neutral contracts. `kmedia-bridge-ffmpeg` contains the optional Kotlin backend and transitively selects one platform client:

```text
kmedia-bridge-ffmpeg
├── kmedia-bridge-client-android  ─┐
├── kmedia-bridge-client-desktop  ─┼─ exact KMediaFfmpegRuntime 0.1.0-rc.2
└── kmedia-bridge-api             ─┘
```

The Android client AAR contains one `libkmediabridge.so` for each supported ARM ABI. The desktop client JAR contains one bridge library for Linux x86_64/ARM64, Windows x86_64 and macOS ARM64. It contains no FFmpeg-family libraries.

The shared runtime is initialized first. Its process-global runtime ID is compared with `sharedRuntimeId` in the client manifest before native client loading. The loader verifies the platform, bridge ABI, closed library inventory and SHA-256. Shared libraries use prefixed names supplied only by KMediaFfmpegRuntime.

Linux clients use `$ORIGIN`, macOS uses `@rpath`, and Windows loads the shared runtime DLLs before the bridge DLL. Client and runtime SDKs are immutable release artifacts; the build accepts an SDK directory and never fetches a Git submodule.
