# Contributing

Every change must preserve the internal-core/LGPL-runtime boundary and pass:

```shell
./gradlew check complianceCheck
```

Changes to FFmpeg versions, flags, external components, native linking, or
binary packaging require matching updates to the compliance manifest, source
offer, notices, SBOM, and runtime inspection tests. GPL or nonfree FFmpeg
features are not accepted in official artifacts.

Do not move first-party Kotlin core code into the LGPL runtime publication or
publish 0.4.0-or-later core artifacts to Pages or Maven Central.

Never commit media URLs, credentials, signing material, private test media, or
production configuration. Security issues should follow [SECURITY.md](SECURITY.md).
