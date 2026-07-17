# Releasing

1. Update the pinned FFmpeg source only after checking the upstream release and
   exact SHA-256.
2. Run `./gradlew clean check complianceCheck` locally.
3. Merge only after CI builds and inspects Linux x64, macOS arm64/x64, and
   Windows x64. Runnable desktop hosts must also load, probe, and stream the
   packaged runtime through the JVM API.
4. Configure the chosen private Maven service outside the repository. Release
   CI reads repository variables `KMEDIA_BRIDGE_PRIVATE_MAVEN_URL` and
   `KMEDIA_BRIDGE_PRIVATE_MAVEN_USERNAME`, plus the
   `KMEDIA_BRIDGE_PRIVATE_MAVEN_PASSWORD` repository secret. Their values come
   from that Maven service and are never committed, echoed, or written into an
   artifact.
   GitHub's Maven registry inherits the source repository's visibility, so the
   current public KMediaBridge repository is not a private package target.
5. Create an immutable stable tag such as `v0.4.0`.
6. Tag CI rebuilds every native payload, embeds per-platform manifests, merges
   the aggregate binary manifest, reruns publication verification, and then
   publishes only the LGPL runtime to the version-preserving `gh-pages`
   repository. The internal API and Kotlin backend are published only to the
   configured private Maven repository.
7. The GitHub release receives the exact FFmpeg archive, platform bundles,
   native classpath resources, complete compliance source bundle, CycloneDX
   SBOM, and SHA-256 inventory.
8. Optional Maven Central publication reuses the native resources from that
   immutable GitHub release and publishes only the LGPL runtime. It is allowed
   only after signing and Central Portal secret names have been configured
   manually by the maintainer outside Codex.

Published versions are immutable. A broken release is superseded by a new
version; artifacts are never replaced in place.

The public 0.3.0 core artifacts are historical LGPL releases and are not
deleted or overwritten. The mixed-license boundary begins with 0.4.0.
