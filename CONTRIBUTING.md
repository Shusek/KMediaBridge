# Contributing

Every change must preserve the fail-closed LGPL boundary and pass:

```shell
./gradlew check complianceCheck
```

Changes to FFmpeg versions, flags, external components, native linking, or
binary packaging require matching updates to the compliance manifest, source
offer, notices, SBOM, and runtime inspection tests. GPL or nonfree FFmpeg
features are not accepted in official artifacts.

Never commit media URLs, credentials, signing material, private test media, or
production configuration. Security issues should follow [SECURITY.md](SECURITY.md).
