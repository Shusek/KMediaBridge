#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
work_dir_argument="${1:-$root_dir/build/native-work-windows-x64}"
output_dir_argument="${2:-$root_dir/build/native-dist-windows-x64}"
mkdir -p "$work_dir_argument" "$output_dir_argument"
work_dir="$(cd "$work_dir_argument" && pwd)"
output_dir="$(cd "$output_dir_argument" && pwd)"
source_version="8.1.2"
source_name="ffmpeg-$source_version.tar.xz"
source_url="https://ffmpeg.org/releases/$source_name"
source_sha256="464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
source_archive="$work_dir/$source_name"
source_dir="$work_dir/ffmpeg-$source_version"
prefix_dir="$work_dir/prefix"
cross_prefix="x86_64-w64-mingw32-"

for tool in "${cross_prefix}gcc" "${cross_prefix}ar" "${cross_prefix}objdump" make; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Required Windows cross-build tool is missing: $tool" >&2
        exit 1
    fi
done

if [[ ! -f "$source_archive" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 --output "$source_archive" "$source_url"
fi

actual_sha256="$(sha256sum "$source_archive" | awk '{print $1}')"
if [[ "$actual_sha256" != "$source_sha256" ]]; then
    echo "FFmpeg source SHA-256 mismatch." >&2
    exit 1
fi

rm -rf "$source_dir" "$prefix_dir"
rm -rf "$output_dir/lib" "$output_dir/include" "$output_dir/compliance"
tar -C "$work_dir" -xf "$source_archive"
mkdir -p "$prefix_dir" "$output_dir/lib" "$output_dir/include" "$output_dir/compliance/source"

configure_arguments=(
    "--prefix=$prefix_dir"
    # Avoid resolving against FFmpeg DLLs installed by VLC or another player.
    "--build-suffix=-kmb"
    "--target-os=mingw32"
    "--arch=x86_64"
    "--cross-prefix=$cross_prefix"
    "--disable-gpl"
    "--disable-nonfree"
    "--disable-programs"
    "--disable-doc"
    "--disable-debug"
    "--disable-static"
    "--enable-shared"
    "--disable-autodetect"
    "--disable-network"
    "--disable-x86asm"
    "--disable-everything"
    "--disable-avdevice"
    "--disable-avfilter"
    "--disable-swresample"
    "--disable-swscale"
    "--enable-avutil"
    "--enable-avcodec"
    "--enable-avformat"
    "--enable-demuxer=matroska,mov,mpegts"
    "--enable-muxer=mp4"
    "--enable-protocol=file"
    "--enable-parser=aac,h264,hevc,opus"
    "--enable-bsf=aac_adtstoasc,extract_extradata,hevc_metadata"
)

(
    cd "$source_dir"
    ./configure "${configure_arguments[@]}"
    make -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
    make install
)

"${cross_prefix}gcc" \
    -std=c11 \
    -O2 \
    -Wall \
    -Wextra \
    -Werror \
    -fvisibility=hidden \
    -I "$root_dir/native/include" \
    -I "$prefix_dir/include" \
    -shared \
    "$root_dir/native/src/kmedia_bridge.c" \
    "$root_dir/native/src/kmedia_bridge_subtitles.c" \
    -L "$prefix_dir/lib" \
    -Wl,--no-undefined \
    -Wl,--out-implib,"$output_dir/lib/libkmediabridge.dll.a" \
    -lavformat-kmb \
    -lavcodec-kmb \
    -lavutil-kmb \
    -o "$output_dir/lib/kmediabridge.dll"

cp "$prefix_dir"/bin/avcodec-kmb-*.dll "$output_dir/lib/"
cp "$prefix_dir"/bin/avformat-kmb-*.dll "$output_dir/lib/"
cp "$prefix_dir"/bin/avutil-kmb-*.dll "$output_dir/lib/"
for runtime_name in libgcc_s_seh-1.dll libwinpthread-1.dll; do
    runtime_path="$("${cross_prefix}gcc" -print-file-name="$runtime_name")"
    if [[ -f "$runtime_path" ]]; then
        cp "$runtime_path" "$output_dir/lib/"
    fi
done

cp "$root_dir/native/include/kmedia_bridge.h" "$output_dir/include/kmedia_bridge.h"
cp "$source_archive" "$output_dir/compliance/source/$source_name"
cp "$source_dir/COPYING.LGPLv2.1" "$output_dir/compliance/COPYING.LGPLv2.1"
cp "$source_dir/LICENSE.md" "$output_dir/compliance/FFMPEG_LICENSE.md"
cp "$source_dir/config.h" "$output_dir/compliance/ffmpeg-config.h"
cp "$source_dir/config_components.h" "$output_dir/compliance/ffmpeg-config-components.h"
printf '%s\n' "${configure_arguments[@]}" > "$output_dir/compliance/ffmpeg-configure-arguments.txt"

mkdir -p \
    "$output_dir/compliance/kmediabridge-source/native/include" \
    "$output_dir/compliance/kmediabridge-source/native/src" \
    "$output_dir/compliance/kmediabridge-source/docs" \
    "$output_dir/compliance/kmediabridge-source/scripts"
cp "$root_dir/LICENSE" "$output_dir/compliance/kmediabridge-source/LICENSE"
cp "$root_dir/NOTICE" "$output_dir/compliance/kmediabridge-source/NOTICE"
cp "$root_dir/THIRD_PARTY_NOTICES.md" "$output_dir/compliance/kmediabridge-source/THIRD_PARTY_NOTICES.md"
cp "$root_dir/docs/COMPLIANCE.md" "$output_dir/compliance/kmediabridge-source/docs/COMPLIANCE.md"
cp "$root_dir/docs/RELINKING.md" "$output_dir/compliance/kmediabridge-source/docs/RELINKING.md"
cp "$root_dir/native/CMakeLists.txt" "$output_dir/compliance/kmediabridge-source/native/CMakeLists.txt"
cp "$root_dir/native/build-ffmpeg-unix.sh" "$output_dir/compliance/kmediabridge-source/native/build-ffmpeg-unix.sh"
cp "$root_dir/native/build-ffmpeg-windows-x64.sh" "$output_dir/compliance/kmediabridge-source/native/build-ffmpeg-windows-x64.sh"
cp "$root_dir/native/build-subtitle-deps-unix.sh" "$output_dir/compliance/kmediabridge-source/native/build-subtitle-deps-unix.sh"
cp "$root_dir/native/include/kmedia_bridge.h" "$output_dir/compliance/kmediabridge-source/native/include/kmedia_bridge.h"
cp "$root_dir/native/src/kmedia_bridge.c" "$output_dir/compliance/kmediabridge-source/native/src/kmedia_bridge.c"
cp "$root_dir/native/src/kmedia_bridge_subtitles.c" "$output_dir/compliance/kmediabridge-source/native/src/kmedia_bridge_subtitles.c"
cp "$root_dir/scripts/generate_native_checksums.py" "$output_dir/compliance/kmediabridge-source/scripts/generate_native_checksums.py"
cp "$root_dir/scripts/inspect_native_runtime.py" "$output_dir/compliance/kmediabridge-source/scripts/inspect_native_runtime.py"
cp "$root_dir/scripts/package_native_runtime.py" "$output_dir/compliance/kmediabridge-source/scripts/package_native_runtime.py"
cp "$root_dir/scripts/assemble_runtime_manifest.py" "$output_dir/compliance/kmediabridge-source/scripts/assemble_runtime_manifest.py"
cp "$root_dir/scripts/verify_ffmpeg_release.sh" "$output_dir/compliance/kmediabridge-source/scripts/verify_ffmpeg_release.sh"

"${cross_prefix}gcc" --version > "$output_dir/compliance/compiler-version.txt"
if ! git -C "$root_dir" rev-parse --verify HEAD > "$output_dir/compliance/build-recipe-revision.txt" 2>/dev/null; then
    printf '%s\n' "uncommitted-source" > "$output_dir/compliance/build-recipe-revision.txt"
fi

python3 "$root_dir/scripts/inspect_native_runtime.py" \
    --library "$output_dir/lib/kmediabridge.dll" \
    --fallback-library "$output_dir/lib/kmediabridge.dll" \
    --source-sha256 "$source_sha256" \
    --target windows \
    --expected-version "$source_version" \
    --output "$output_dir/compliance/runtime-inspection.json"

python3 "$root_dir/scripts/generate_native_checksums.py" \
    --root "$output_dir" \
    --output "$output_dir/compliance/SHA256SUMS"

echo "LGPL Windows x64 native bridge staged in $output_dir"
