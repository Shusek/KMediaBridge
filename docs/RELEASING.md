# Releasing

1. Update the pinned FFmpeg source only after checking the upstream release and
   exact SHA-256.
2. Run `./gradlew clean check complianceCheck` locally.
3. Merge only after CI builds and inspects Linux x64, macOS arm64/x64, and
   Windows x64. Runnable desktop hosts must also load, probe, and stream the
   packaged runtime through the JVM API.
4. Create an immutable stable tag such as `v0.3.0`.
5. Tag CI rebuilds every native payload, embeds per-platform manifests, merges
   the aggregate binary manifest, reruns publication verification, and then
   publishes all three Maven artifacts to the version-preserving `gh-pages`
   repository.
6. The GitHub release receives the exact FFmpeg archive, platform bundles,
   native classpath resources, complete compliance source bundle, CycloneDX
   SBOM, and SHA-256 inventory.
7. Maven Central publication reuses the native resources from that immutable
   GitHub release. It is allowed only after signing and Central Portal secret
   names have been configured manually by the maintainer outside Codex.

Published versions are immutable. A broken release is superseded by a new
version; artifacts are never replaced in place.
