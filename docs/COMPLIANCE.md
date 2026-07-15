# Compliance policy

KMediaBridge follows a fail-closed distribution policy. A CI job must not
publish a native payload unless every item below is present and internally
consistent.

## Project license

- All KMediaBridge source is LGPL-2.1-or-later.
- Source files carry an SPDX identifier.
- Maven POMs identify LGPL-2.1-or-later and link to the repository license.
- Application licenses must not impose restrictions on the rights granted for
  KMediaBridge or redistributed FFmpeg components.

## FFmpeg build boundary

- Use a signed upstream FFmpeg release and pin its SHA-256.
- Configure with explicit `--disable-gpl` and `--disable-nonfree`.
- Do not enable x264, x265, Xvid, vid.stab, frei0r, Rubber Band, libcdio,
  davs2/xavs, smbclient, or another component that changes the effective
  license without a fresh legal review.
- Link FFmpeg dynamically to the native driver.
- Record the complete configure line and every linked component.
- Query the built libraries for their reported license and configuration;
  never trust a filename or a manually entered label alone.

## Required payload evidence

Every native payload release must contain or link immutably to:

1. The exact FFmpeg source archive used for the binary.
2. Source archives for every statically linked external component.
3. The immutable KMediaBridge build recipe revision and all patches.
4. The full configure line and compiler/linker versions.
5. LGPL texts and third-party notices.
6. SHA-256 hashes for sources and native files.
7. A CycloneDX SBOM.
8. A source offer hosted beside the downloadable binary.
9. Replacement/relinking instructions appropriate to the platform.

The official FFmpeg legal checklist remains the upstream baseline:
https://ffmpeg.org/legal.html

## Patent and store policy

LGPL compliance does not grant patent rights for HEVC, Dolby Vision, AAC, or
other formats. App-store signing and anti-circumvention terms must be reviewed
separately, particularly for iOS. A platform payload remains unpublished until
that review and its replacement/relinking procedure are documented.

## Secret handling

Builds use only secret *names* in workflow definitions. No credential value,
signed URL, media access token, or private package credential belongs in the
repository, command line, build log, SBOM, manifest, or source offer.
