# Compliance boundary

KMediaBridge publishes only its independent Kotlin code and thin native client. FFmpeg 8.1.2, libass, FreeType, FriBidi and HarfBuzz are conveyed once by KMediaFfmpegRuntime under their own notices.

The shared runtime release is responsible for the FFmpeg signature check, disabled GPL/version3/nonfree configuration, dynamic-only libraries, corresponding source, build arguments, patches, SBOM, signatures and checksums. KMediaBridge verifies the exact runtime version and ID both while building the client and before loading it.

The release gate rejects:

- private FFmpeg build recipes or native binaries committed here;
- unsupported Android or macOS architectures;
- unprefixed/legacy FFmpeg dependencies in client binaries;
- a client payload containing anything beyond the single bridge library;
- dependency metadata that does not strictly pin the shared runtime;
- a stable release lacking the physical-device Android ARM test attestation.

For RCs, hosted release jobs build and verify the bridge client against the
exact hash-bound runtime for both Android ARM ABIs, and aggregation requires
both artifacts. This proves client inventory, architecture, and shared-runtime
linkage without an emulator or Rosetta. It does not claim Android framework,
MediaCodec, or playback execution. Stable releases additionally require the
complete physical-device ARM framework and playback matrix. x86 never
substitutes for either ARM ABI.

Full desktop compatibility clients on macOS and Windows must authenticate their
native features, dynamically link the shared runtime's `avfilter`, `swscale`
and `swresample` boundaries, and advertise both video and audio transcoding.
Their client manifests must include AVI and ASF input capabilities; release
verification rejects a partial declaration. The shared runtime remains solely
responsible for FFmpeg configuration, component and source evidence, SBOM,
signing, and replacement artifacts.

The API/backend retain `LicenseRef-KMediaBridge-Internal`; source files explicitly marked `LGPL-2.1-or-later` retain that license. See the root license map and `LICENSES/` for exact terms.
