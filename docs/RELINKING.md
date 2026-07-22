# Rebuilding the client

Download the SDK for the exact KMediaFfmpegRuntime release and verify its published SHA-256. Then build only the bridge client:

```shell
python3 -B native/build-client.py \
  --target macos-aarch64 \
  --runtime-sdk /path/to/kmedia-ffmpeg-runtime-sdk \
  --runtime-version 0.1.0-rc.1 \
  --runtime-source-sha256 SHA256_FROM_THE_RUNTIME_RELEASE \
  --version 0.5.0-rc.1 \
  --revision FULL_GIT_COMMIT \
  --output build/client
```

Android additionally requires `--ndk /path/to/android-ndk`. Valid Android targets are `android-arm64-v8a` and `android-armeabi-v7a`.

The builder verifies `runtime.properties`, component versions, target ABI and runtime ID; links dynamically against the prefixed SDK libraries; produces one bridge library; emits a client SDK and manifest; and runs dependency/architecture inspection. It does not build the shared runtime.

To replace the shared libraries themselves, use the corresponding-source bundle and instructions published by KMediaFfmpegRuntime. The replacement must preserve its ABI and runtime ID contract or both clients reject it before loading.
