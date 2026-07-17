# Compliance policy

KMediaBridge follows a fail-closed distribution policy. A CI job must not
publish a native payload unless every item below is present and internally
consistent.

This policy governs native payloads conveyed by KMediaBridge. An application
may explicitly select a compatible external runtime, including a GPL build,
without causing KMediaBridge to distribute that runtime. External payloads are
marked `CALLER_PROVIDED`: the loader still verifies their ABI, file boundaries,
hashes, and runtime identity, while licensing and distribution analysis remain
the caller's responsibility. Passing the loader is not a compliance
certification.

## License boundary

- `api/**` and the first-party Kotlin implementation in `ffmpeg/**` use
  `LicenseRef-KMediaBridge-Internal`.
- `native/**`, the runtime artifact, rebuild/relinking material, and compliance
  tooling remain LGPL-2.1-or-later.
- Source files carry the SPDX identifier assigned by the root `LICENSE` map.
- Core POMs identify the internal-use license; the runtime POM identifies
  LGPL-2.1-or-later. All three coordinates may be publicly distributed by
  Maven Central without changing those terms.
- Publication verification stages the two license scopes separately and fails
  if a 0.4.0-or-later core artifact enters the runtime-only Pages repository
  or if native payloads enter a core archive.
- Application licenses must not impose restrictions on the rights granted for
  the native bridge, FFmpeg, FriBidi, or other redistributed LGPL components.

## FFmpeg build boundary

The following requirements apply to payloads published by KMediaBridge:

- Use a signed upstream FFmpeg release, pin its SHA-256, and verify its detached
  signature against fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8`.
- Configure with explicit `--disable-gpl` and `--disable-nonfree`.
- Do not enable x264, x265, Xvid, vid.stab, frei0r, Rubber Band, libcdio,
  davs2/xavs, smbclient, or another component that changes the effective
  license without a fresh legal review.
- Link FFmpeg dynamically to the native driver.
- Record the complete configure line and every linked component.
- Query the built libraries for their reported license and configuration;
  never trust a filename or a manually entered label alone.
- Keep the optional subtitle stack limited to the pinned FTL/LGPL/MIT/Zlib/ISC
  components in `compliance/subtitles/manifest.json`. FriBidi tools are GPL and
  are not built or conveyed; only its LGPL library is linked.
- A subtitle-capable payload must report `subtitleBurnIn=true` from the native
  ABI and carry matching manifest capabilities. The loader rejects a mismatch.

## Required payload evidence

Every native payload release must contain or link immutably to:

1. The exact FFmpeg source archive used for the binary.
2. Source archives for every statically linked external component.
3. The immutable KMediaBridge build recipe revision and all patches.
4. The full configure line and compiler/linker versions.
5. Complete LGPL texts and third-party notices.
6. SHA-256 hashes for sources and native files.
7. A CycloneDX SBOM.
8. A source offer hosted beside the downloadable binary.
9. Replacement/relinking instructions appropriate to the platform.

Stable desktop releases publish a single optional Maven runtime JAR plus
separate replaceable platform archives. The JAR's aggregate manifest must list
macOS arm64/x64, Linux x64, and Windows x64 and must match every native entry's
SHA-256. Release publication fails if a static library or executable is found.

The official FFmpeg legal checklist remains the upstream baseline:
https://ffmpeg.org/legal.html

## Patent and store policy

LGPL compliance does not grant patent rights for HEVC, Dolby Vision, AAC, or
other formats. App-store signing and anti-circumvention terms must be reviewed
separately, particularly for iOS. A platform payload remains unpublished until
that review and its replacement/relinking procedure are documented.

## Secret handling

Builds use only secret *names* in workflow definitions. Maven Central
publication receives the Central Portal token and in-memory signing material
from external CI configuration. No credential value, signing key, signed URL,
media access token, or package credential belongs in the repository, command
line, build log, SBOM, manifest, or source offer.
