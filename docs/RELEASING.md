# Releasing

1. Update the pinned FFmpeg source only after checking the upstream release and
   exact SHA-256.
2. Run `./gradlew clean check complianceCheck` locally.
3. Merge only after CI builds and inspects Linux x64, macOS arm64/x64, Windows
   x64, and Android arm64. Runnable desktop hosts must also load, probe, and
   stream the packaged runtime through the JVM API. The stable tag additionally
   rebuilds Android arm64-v8a, armeabi-v7a, and x86_64.
4. Configure the Central Portal token and signing material outside the
   repository. Central CI reads only the repository secret names
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_SIGNING_KEY`, and
   `MAVEN_SIGNING_PASSWORD`. Their values are never committed, echoed, or
   written into an artifact.
5. Create an immutable stable tag such as `v0.4.2`.
6. Tag CI rebuilds every native payload, embeds per-platform manifests, merges
   the aggregate binary manifest, reruns publication verification, and then
   publishes only the two LGPL runtimes to the version-preserving `gh-pages`
   repository.
7. The GitHub release receives the exact FFmpeg archive, platform bundles,
   native classpath resources, complete compliance source bundle, CycloneDX
   SBOM, and SHA-256 inventory.
8. Maven Central publication reuses both desktop and Android native resources
   from that immutable GitHub release and publishes the API, Kotlin backend,
   desktop runtime, and Android runtime in one Gradle invocation and one
   Central deployment. Every artifact is signed, and every POM declares its own
   license. Public downloadability of the API and backend does not change their
   internal-use terms.

Published versions are immutable. A broken release is superseded by a new
version; artifacts are never replaced in place.

The public 0.3.0 core artifacts are historical LGPL releases and are not
deleted or overwritten. The mixed-license boundary begins with 0.4.0; its
core remains internal-use even though its coordinates are public.
