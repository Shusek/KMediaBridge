# Compliance boundary

KMediaBridge publishes only its independent Kotlin code and thin native client. FFmpeg 8.1.2, libass, FreeType, FriBidi and HarfBuzz are conveyed once by KMediaFfmpegRuntime under their own notices.

The shared runtime release is responsible for the FFmpeg signature check, disabled GPL/version3/nonfree configuration, dynamic-only libraries, corresponding source, build arguments, patches, SBOM, signatures and checksums. KMediaBridge verifies the exact runtime version and ID both while building the client and before loading it.

The release gate rejects:

- private FFmpeg build recipes or native binaries committed here;
- unsupported Android or macOS architectures;
- unprefixed/legacy FFmpeg dependencies in client binaries;
- a client payload containing anything beyond the single bridge library;
- dependency metadata that does not strictly pin the shared runtime;
- a release lacking the local Android ARM test attestation.

An RC may use KMediaFfmpegRuntime's path-free pure-ARMv7 native graph report
when no API 28 ARMv7 device is available. This exception never permits x86 and
does not satisfy the stable-release device, framework, or MediaCodec matrix.

The API/backend retain `LicenseRef-KMediaBridge-Internal`; source files explicitly marked `LGPL-2.1-or-later` retain that license. See the root license map and `LICENSES/` for exact terms.
