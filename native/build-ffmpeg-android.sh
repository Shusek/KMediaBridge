#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
ndk_root="${1:?Usage: build-ffmpeg-android.sh <ndk-root> [work-dir] [output-dir] [abis] [source-archive]}"
work_dir_argument="${2:-$root_dir/build/native-work-android}"
output_dir_argument="${3:-$root_dir/build/native-dist-android}"
abi_list="${4:-arm64-v8a,armeabi-v7a,x86_64}"
source_version="8.1.2"
source_name="ffmpeg-$source_version.tar.xz"
source_url="https://ffmpeg.org/releases/$source_name"
source_sha256="464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
api_level="23"

mkdir -p "$work_dir_argument" "$output_dir_argument"
work_dir="$(cd "$work_dir_argument" && pwd)"
output_dir="$(cd "$output_dir_argument" && pwd)"
source_archive="${5:-$work_dir/$source_name}"

case "$(uname -s)" in
    Darwin) host_tag="darwin-x86_64" ;;
    Linux) host_tag="linux-x86_64" ;;
    *) echo "Android native builds are supported from macOS or Linux hosts." >&2; exit 1 ;;
esac

toolchain="$ndk_root/toolchains/llvm/prebuilt/$host_tag"
if [[ ! -x "$toolchain/bin/llvm-ar" || ! -x "$toolchain/bin/llvm-readelf" ]]; then
    echo "The requested Android NDK LLVM toolchain is unavailable: $toolchain" >&2
    exit 1
fi

if [[ ! -f "$source_archive" ]]; then
    if [[ "$source_archive" != "$work_dir/$source_name" ]]; then
        echo "The explicitly selected FFmpeg source archive does not exist." >&2
        exit 1
    fi
    curl --fail --location --proto '=https' --tlsv1.2 --output "$source_archive" "$source_url"
fi

if command -v shasum >/dev/null 2>&1; then
    actual_sha256="$(shasum -a 256 "$source_archive" | awk '{print $1}')"
else
    actual_sha256="$(sha256sum "$source_archive" | awk '{print $1}')"
fi
if [[ "$actual_sha256" != "$source_sha256" ]]; then
    echo "FFmpeg source SHA-256 mismatch." >&2
    exit 1
fi

rm -rf "$output_dir/jniLibs" "$output_dir/compliance"
mkdir -p "$output_dir/jniLibs" "$output_dir/compliance/source" "$output_dir/compliance/abis"
cp "$source_archive" "$output_dir/compliance/source/$source_name"
cp "$root_dir/LICENSES/LGPL-2.1-or-later.txt" "$output_dir/compliance/COPYING.KMEDIABRIDGE.LGPLv2.1"
cp \
    "$root_dir/ffmpeg-runtime-android/src/main/resources/META-INF/kmediabridge/NOTICE" \
    "$output_dir/compliance/NOTICE"
cp "$root_dir/THIRD_PARTY_NOTICES.md" "$output_dir/compliance/THIRD_PARTY_NOTICES.md"
cp "$root_dir/native/patches/ffmpeg-8.1.2-mediacodec-p010.patch" "$output_dir/compliance/"

IFS=',' read -r -a requested_abis <<< "$abi_list"
for abi in "${requested_abis[@]}"; do
    case "$abi" in
        arm64-v8a)
            architecture="aarch64"
            cpu="armv8-a"
            compiler_target="aarch64-linux-android"
            architecture_flag=""
            ;;
        armeabi-v7a)
            architecture="arm"
            cpu="armv7-a"
            compiler_target="armv7a-linux-androideabi"
            architecture_flag="--enable-thumb"
            ;;
        x86_64)
            architecture="x86_64"
            cpu="x86-64"
            compiler_target="x86_64-linux-android"
            architecture_flag="--disable-x86asm"
            ;;
        *)
            echo "Unsupported Android ABI: $abi" >&2
            exit 1
            ;;
    esac

    cc="$toolchain/bin/${compiler_target}${api_level}-clang"
    cxx="$toolchain/bin/${compiler_target}${api_level}-clang++"
    source_dir="$work_dir/source-$abi"
    prefix_dir="$work_dir/prefix-$abi"
    abi_output="$output_dir/jniLibs/$abi"
    abi_compliance="$output_dir/compliance/abis/$abi"
    rm -rf "$source_dir" "$prefix_dir" "$abi_output" "$abi_compliance"
    mkdir -p "$source_dir" "$prefix_dir" "$abi_output" "$abi_compliance"
    tar -C "$source_dir" --strip-components=1 -xf "$source_archive"
    patch --batch --forward -d "$source_dir" -p1 \
        < "$root_dir/native/patches/ffmpeg-8.1.2-mediacodec-p010.patch"

    configure_arguments=(
        "--prefix=$prefix_dir"
        "--target-os=android"
        "--arch=$architecture"
        "--cpu=$cpu"
        "--cc=$cc"
        "--cxx=$cxx"
        "--ld=$cc"
        "--ar=$toolchain/bin/llvm-ar"
        "--nm=$toolchain/bin/llvm-nm"
        "--ranlib=$toolchain/bin/llvm-ranlib"
        "--strip=$toolchain/bin/llvm-strip"
        "--sysroot=$toolchain/sysroot"
        "--enable-cross-compile"
        "--enable-pic"
        "--build-suffix=-kmb"
        "--disable-gpl"
        "--disable-nonfree"
        "--disable-programs"
        "--disable-doc"
        "--disable-debug"
        "--disable-static"
        "--enable-shared"
        "--disable-autodetect"
        "--disable-network"
        "--disable-everything"
        "--disable-avdevice"
        "--disable-avfilter"
        "--disable-swresample"
        "--enable-avutil"
        "--enable-avcodec"
        "--enable-avformat"
        "--enable-swscale"
        "--enable-jni"
        "--enable-mediacodec"
        "--enable-pthreads"
        "--enable-demuxer=matroska,mov,mpegts"
        "--enable-muxer=mp4"
        "--enable-protocol=file"
        "--enable-decoder=h264,hevc,vp9,av1,h264_mediacodec,hevc_mediacodec,vp9_mediacodec,av1_mediacodec"
        "--enable-encoder=h264_mediacodec"
        "--enable-parser=aac,h264,hevc,opus,vp9,av1"
        "--enable-bsf=aac_adtstoasc,extract_extradata,h264_mp4toannexb,h264_metadata,hevc_mp4toannexb,hevc_metadata"
        "--extra-cflags=-fPIC"
        "--extra-ldflags=-Wl,--exclude-libs,ALL"
        "--extra-libs=-landroid -lmediandk -llog -lz -lm -ldl"
    )
    if [[ -n "$architecture_flag" ]]; then
        configure_arguments+=("$architecture_flag")
    fi

    (
        cd "$source_dir"
        ./configure "${configure_arguments[@]}"
        make -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
        make install
    )

    "$cc" \
        -std=c11 \
        -O3 \
        -Wall \
        -Wextra \
        -Werror \
        -fPIC \
        -fvisibility=hidden \
        -DKMB_ENABLE_HDR_TO_SDR=1 \
        -I "$root_dir/native/include" \
        -I "$root_dir/native/src" \
        -I "$prefix_dir/include" \
        -shared \
        "$root_dir/native/src/kmedia_bridge.c" \
        "$root_dir/native/src/kmedia_bridge_subtitles.c" \
        "$root_dir/native/src/kmedia_bridge_hdr_math.c" \
        "$root_dir/native/src/kmedia_bridge_tonemap.c" \
        "$root_dir/native/android/kmedia_bridge_android_jni.c" \
        -L "$prefix_dir/lib" \
        -Wl,--no-undefined \
        -Wl,-soname,libkmediabridge.so \
        -Wl,--exclude-libs,ALL \
        -lavformat-kmb \
        -lavcodec-kmb \
        -lswscale-kmb \
        -lavutil-kmb \
        -landroid \
        -lmediandk \
        -llog \
        -lz \
        -lm \
        -ldl \
        -o "$abi_output/libkmediabridge.so"

    for library in libavutil-kmb.so libavcodec-kmb.so libavformat-kmb.so libswscale-kmb.so; do
        cp -L "$prefix_dir/lib/$library" "$abi_output/$library"
    done
    "$toolchain/bin/llvm-strip" --strip-unneeded "$abi_output"/*.so

    for library in "$abi_output"/*.so; do
        elf_header="$("$toolchain/bin/llvm-readelf" -h "$library")"
        if ! grep -Eq 'Type:[[:space:]]+DYN' <<< "$elf_header"; then
            echo "Android runtime payload is not a shared object: $library" >&2
            exit 1
        fi
    done
    bridge_dynamic_section="$("$toolchain/bin/llvm-readelf" -d "$abi_output/libkmediabridge.so")"
    for dependency in \
        libavformat-kmb.so \
        libavcodec-kmb.so \
        libswscale-kmb.so \
        libavutil-kmb.so; do
        if ! grep -Fq "Shared library: [$dependency]" <<< "$bridge_dynamic_section"; then
            echo "Android bridge is not dynamically linked to $dependency." >&2
            exit 1
        fi
    done
    printf '%s\n' "$bridge_dynamic_section" > "$abi_compliance/dynamic-link-inspection.txt"

    cp "$source_dir/COPYING.LGPLv2.1" "$abi_compliance/COPYING.FFMPEG.LGPLv2.1"
    cp "$source_dir/LICENSE.md" "$abi_compliance/FFMPEG_LICENSE.md"
    cp "$source_dir/config.h" "$abi_compliance/ffmpeg-config.h"
    cp "$source_dir/config_components.h" "$abi_compliance/ffmpeg-config-components.h"
    printf '%s\n' "${configure_arguments[@]}" > "$abi_compliance/ffmpeg-configure-arguments.txt"
    "$cc" --version > "$abi_compliance/compiler-version.txt"
    for library in "$abi_output"/*.so; do
        if command -v shasum >/dev/null 2>&1; then
            shasum -a 256 "$library"
        else
            sha256sum "$library"
        fi
    done > "$abi_compliance/SHA256SUMS"
done

mkdir -p \
    "$output_dir/compliance/kmediabridge-source/native/android" \
    "$output_dir/compliance/kmediabridge-source/native/include" \
    "$output_dir/compliance/kmediabridge-source/native/patches" \
    "$output_dir/compliance/kmediabridge-source/native/src"
cp "$root_dir/LICENSES/LGPL-2.1-or-later.txt" "$output_dir/compliance/kmediabridge-source/LICENSE"
cp \
    "$root_dir/ffmpeg-runtime-android/src/main/resources/META-INF/kmediabridge/NOTICE" \
    "$output_dir/compliance/kmediabridge-source/NOTICE"
cp "$root_dir/THIRD_PARTY_NOTICES.md" "$output_dir/compliance/kmediabridge-source/THIRD_PARTY_NOTICES.md"
cp "$root_dir/native/build-ffmpeg-android.sh" "$output_dir/compliance/kmediabridge-source/native/"
cp "$root_dir/native/CMakeLists.txt" "$output_dir/compliance/kmediabridge-source/native/"
cp "$root_dir/native/android/kmedia_bridge_android_jni.c" \
    "$output_dir/compliance/kmediabridge-source/native/android/"
cp "$root_dir/native/include/kmedia_bridge.h" "$output_dir/compliance/kmediabridge-source/native/include/"
cp "$root_dir/native/patches/ffmpeg-8.1.2-mediacodec-p010.patch" \
    "$output_dir/compliance/kmediabridge-source/native/patches/"
cp "$root_dir/native/src/kmedia_bridge.c" "$output_dir/compliance/kmediabridge-source/native/src/"
cp "$root_dir/native/src/kmedia_bridge_subtitles.c" "$output_dir/compliance/kmediabridge-source/native/src/"
cp "$root_dir/native/src/kmedia_bridge_tonemap.c" "$output_dir/compliance/kmediabridge-source/native/src/"
cp "$root_dir/native/src/kmedia_bridge_hdr_math.c" "$output_dir/compliance/kmediabridge-source/native/src/"
cp "$root_dir/native/src/kmedia_bridge_hdr_math.h" "$output_dir/compliance/kmediabridge-source/native/src/"
cp "$root_dir/native/src/kmedia_bridge_timestamps.h" "$output_dir/compliance/kmediabridge-source/native/src/"

printf '%s\n' \
    "schemaVersion=1" \
    "abiVersion=4" \
    "ffmpegVersion=$source_version" \
    "ffmpegLicenseSpdx=LGPL-2.1-or-later" \
    "ffmpegSourceUrl=$source_url" \
    "ffmpegSourceSha256=$source_sha256" \
    "dynamicLinking=true" \
    "dynamicLinkingVerified=true" \
    "feature.hdrToSdrToneMap=true" \
    "feature.subtitleBurnIn=false" \
    "android.minSdk=$api_level" \
    "android.abis=$abi_list" \
    > "$output_dir/compliance/manifest.properties"

echo "LGPL Android FFmpeg runtime staged in $output_dir"
