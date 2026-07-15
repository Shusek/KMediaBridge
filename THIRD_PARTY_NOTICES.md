# Third-party notices

## FFmpeg

KMediaBridge contains source code for an optional native bridge to FFmpeg.
The first `0.1.x` source distribution does not include an FFmpeg binary.

FFmpeg is licensed under LGPL-2.1-or-later by default. Optional FFmpeg build
features can change the resulting binary to GPL or make it non-redistributable.
KMediaBridge therefore accepts only builds that explicitly use
`--disable-gpl` and `--disable-nonfree` and pass the repository compliance
gate.

- Project: https://ffmpeg.org/
- Legal information: https://ffmpeg.org/legal.html
- Pinned source: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz
- Detached signature: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz.asc
- Release-key fingerprint: `FCF986EA15E6E293A5644F10B4322F04D67658D8`
- SHA-256: `464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`

No FFmpeg trademark rights are granted by this project.

## Java Native Access (JNA)

The desktop JVM backend uses JNA to call the narrow native C ABI without an
FFmpeg executable. JNA is offered under `Apache-2.0 OR LGPL-2.1-or-later`;
KMediaBridge elects the LGPL-2.1-or-later option for this distribution.

- Project: https://github.com/java-native-access/jna
- Artifact: `net.java.dev.jna:jna:5.19.1`

## Kotlin and kotlinx.coroutines

Kotlin and kotlinx.coroutines are distributed under the Apache License 2.0.
They are build/runtime dependencies and are not copied into Kotlin/Native
source distributions by this repository except as described by generated
Gradle metadata.

## Gradle Wrapper

The repository includes the unmodified Gradle 9.6.1 wrapper JAR so builds can
bootstrap a pinned Gradle distribution. Gradle is licensed under the Apache
License 2.0. Its license is reproduced at `gradle/wrapper/LICENSE`.

- Project: https://github.com/gradle/gradle
- Pinned source: https://github.com/gradle/gradle/tree/v9.6.1
- Wrapper JAR SHA-256: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
