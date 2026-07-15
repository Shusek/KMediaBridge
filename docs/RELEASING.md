# Releasing

1. Update the pinned FFmpeg source and verify its upstream signature and
   SHA-256 when applicable.
2. Run `./gradlew clean check complianceCheck` on a supported macOS host.
3. Run native build jobs on every target platform.
4. Confirm that each native archive contains the compliance manifest, source
   offer, notices, and matching hashes.
5. Create an immutable stable SemVer tag such as `v0.1.0`.
6. The release workflow publishes Maven artifacts to the version-preserving
   `gh-pages` repository and attaches the compliance bundle to the GitHub
   release.
7. Maven Central publication is allowed only when signing and Central Portal
   credentials have been entered manually by the maintainer outside Codex.

Published versions are immutable. A broken release is superseded by a new
version; artifacts are never replaced in place.
