# Releasing

KMediaBridge `0.5.x` is released only after the exact KMediaFfmpegRuntime version has been published.

1. Run `./gradlew check complianceCheck`.
2. Build the six client targets from the published SDKs and verify every native output.
3. Assemble the two Android ARM clients and four desktop clients.
4. Record the local Android ARM test attestation: tested commit, runtime ID and report SHA-256.
5. Publish `kmedia-bridge-api`, `kmedia-bridge-ffmpeg`, `kmedia-bridge-client-android` and `kmedia-bridge-client-desktop` to Maven Central.
6. Test a consumer resolving only public RC artifacts before promoting a stable release.

The workflow accepts immutable SemVer releases including RCs, never overwrites an existing version, publishes source and SBOM assets, and verifies the final dependency graph. KMediaBridge has no iOS native release target.
