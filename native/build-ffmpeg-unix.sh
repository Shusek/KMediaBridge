#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
work_dir_argument="${1:-$root_dir/build/native-work}"
output_dir_argument="${2:-$root_dir/build/native-dist}"
mkdir -p "$work_dir_argument" "$output_dir_argument"
work_dir="$(cd "$work_dir_argument" && pwd)"
output_dir="$(cd "$output_dir_argument" && pwd)"
runtime_profile="${3:-remux}"
source_version="8.1.2"
source_name="ffmpeg-$source_version.tar.xz"
source_url="https://ffmpeg.org/releases/$source_name"
source_sha256="464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
source_archive="$work_dir/$source_name"
source_dir="$work_dir/ffmpeg-$source_version"
prefix_dir="$work_dir/prefix"
subtitle_prefix_dir="$work_dir/subtitle-prefix"

if [[ "$runtime_profile" != "remux" && "$runtime_profile" != "subtitle-sdr" ]]; then
    echo "Runtime profile must be remux or subtitle-sdr." >&2
    exit 1
fi
if [[ "$runtime_profile" == "subtitle-sdr" && "$(uname -s)" != "Darwin" ]]; then
    echo "The reviewed subtitle-sdr profile currently targets macOS only." >&2
    exit 1
fi

if [[ ! -f "$source_archive" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 --output "$source_archive" "$source_url"
fi

actual_sha256="$(shasum -a 256 "$source_archive" | awk '{print $1}')"
if [[ "$actual_sha256" != "$source_sha256" ]]; then
    echo "FFmpeg source SHA-256 mismatch." >&2
    exit 1
fi

rm -rf "$source_dir" "$prefix_dir"
rm -rf "$output_dir/lib" "$output_dir/include" "$output_dir/compliance"
tar -C "$work_dir" -xf "$source_archive"
mkdir -p "$prefix_dir" "$output_dir/lib" "$output_dir/include" "$output_dir/compliance/source"

# ELF symbol versions form the second half of the isolation boundary. A private
# SONAME prevents accidental file resolution; these namespaces also prevent a
# previously loaded libVLC FFmpeg with the same ABI major from interposing its
# exported av_* symbols into this runtime.
if [[ "$(uname -s)" == "Linux" ]]; then
    while IFS= read -r version_script; do
        sed -i 's/_MAJOR/_KMB_MAJOR/g' "$version_script"
    done < <(find "$source_dir" -mindepth 2 -maxdepth 2 -name 'lib*.v' -type f -print)
fi

configure_arguments=(
    "--prefix=$prefix_dir"
    # Keep our FFmpeg SONAMEs distinct from libVLC's private FFmpeg build.
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
    # The bridge only probes/remuxes; avoiding an external assembler keeps the recipe self-contained.
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

bridge_defines=()
bridge_libraries=(-lavformat-kmb -lavcodec-kmb -lavutil-kmb)
if [[ "$runtime_profile" == "subtitle-sdr" ]]; then
    "$root_dir/native/build-subtitle-deps-unix.sh" "$work_dir/subtitle-deps" "$subtitle_prefix_dir"
    remux_only_arguments=("--disable-avfilter" "--disable-swscale")
    filtered_arguments=()
    for argument in "${configure_arguments[@]}"; do
        if [[ ! " ${remux_only_arguments[*]} " =~ " $argument " ]]; then
            filtered_arguments+=("$argument")
        fi
    done
    configure_arguments=("${filtered_arguments[@]}")
    configure_arguments+=(
        "--enable-avfilter"
        "--enable-swscale"
        "--enable-libass"
        "--pkg-config-flags=--static"
        "--extra-cflags=-I$subtitle_prefix_dir/include"
        "--extra-ldflags=-L$subtitle_prefix_dir/lib"
        "--extra-libs=-lc++"
        "--enable-videotoolbox"
        "--enable-decoder=h264,hevc,vp9,av1,ass,ssa,movtext,subrip,webvtt"
        "--enable-encoder=h264_videotoolbox"
        "--enable-filter=buffer,buffersink,subtitles,scale,format"
    )
    bridge_defines+=("-DKMB_ENABLE_SUBTITLE_BURN_IN=1" "-DKMB_ENABLE_HDR_TO_SDR=1")
    bridge_libraries=(-lavfilter-kmb -lswscale-kmb -lavformat-kmb -lavcodec-kmb -lavutil-kmb)
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
    configure_arguments+=("--install-name-dir=@rpath")
fi

(
    cd "$source_dir"
    PKG_CONFIG_PATH="$subtitle_prefix_dir/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}" \
        ./configure "${configure_arguments[@]}"
    make -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
    make install
)

timestamp_test_binary="$work_dir/kmedia_bridge_timestamps_test"
"${CC:-cc}" \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    -I "$root_dir/native/src" \
    -I "$prefix_dir/include" \
    "$root_dir/native/tests/kmedia_bridge_timestamps_test.c" \
    -L "$prefix_dir/lib" \
    -Wl,-rpath,"$prefix_dir/lib" \
    -lavutil-kmb \
    -o "$timestamp_test_binary"
if [[ "$(uname -s)" == "Darwin" ]]; then
    DYLD_LIBRARY_PATH="$prefix_dir/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}" "$timestamp_test_binary"
else
    LD_LIBRARY_PATH="$prefix_dir/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$timestamp_test_binary"
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
    "${CC:-cc}" \
        -std=c11 \
        -O2 \
        -Wall \
        -Wextra \
        -Werror \
        -fvisibility=hidden \
        "${bridge_defines[@]}" \
        -I "$root_dir/native/include" \
        -I "$prefix_dir/include" \
        -dynamiclib \
        "$root_dir/native/src/kmedia_bridge.c" \
        "$root_dir/native/src/kmedia_bridge_subtitles.c" \
        "$root_dir/native/src/kmedia_bridge_tonemap.c" \
        "$root_dir/native/src/kmedia_bridge_hdr_math.c" \
        -L "$prefix_dir/lib" \
        -Wl,-rpath,@loader_path \
        -Wl,-install_name,@rpath/libkmediabridge.1.dylib \
        "${bridge_libraries[@]}" \
        -o "$output_dir/lib/libkmediabridge.1.0.0.dylib"
    ln -s libkmediabridge.1.0.0.dylib "$output_dir/lib/libkmediabridge.1.dylib"
    ln -s libkmediabridge.1.dylib "$output_dir/lib/libkmediabridge.dylib"
    cp -P "$prefix_dir"/lib/libavcodec-kmb*.dylib "$output_dir/lib/"
    cp -P "$prefix_dir"/lib/libavformat-kmb*.dylib "$output_dir/lib/"
    cp -P "$prefix_dir"/lib/libavutil-kmb*.dylib "$output_dir/lib/"
    if [[ "$runtime_profile" == "subtitle-sdr" ]]; then
        cp -P "$prefix_dir"/lib/libavfilter-kmb*.dylib "$output_dir/lib/"
        cp -P "$prefix_dir"/lib/libswscale-kmb*.dylib "$output_dir/lib/"
    fi
else
    "${CC:-cc}" \
        -std=c11 \
        -O2 \
        -Wall \
        -Wextra \
        -Werror \
        -fPIC \
        -fvisibility=hidden \
        "${bridge_defines[@]}" \
        -I "$root_dir/native/include" \
        -I "$prefix_dir/include" \
        -shared \
        "$root_dir/native/src/kmedia_bridge.c" \
        "$root_dir/native/src/kmedia_bridge_subtitles.c" \
        "$root_dir/native/src/kmedia_bridge_tonemap.c" \
        "$root_dir/native/src/kmedia_bridge_hdr_math.c" \
        -L "$prefix_dir/lib" \
        -Wl,-rpath,'$ORIGIN' \
        -Wl,-soname,libkmediabridge.so.1 \
        "${bridge_libraries[@]}" \
        -o "$output_dir/lib/libkmediabridge.so.1.0.0"
    ln -s libkmediabridge.so.1.0.0 "$output_dir/lib/libkmediabridge.so.1"
    ln -s libkmediabridge.so.1 "$output_dir/lib/libkmediabridge.so"
    cp -P "$prefix_dir"/lib/libavcodec-kmb.so* "$output_dir/lib/"
    cp -P "$prefix_dir"/lib/libavformat-kmb.so* "$output_dir/lib/"
    cp -P "$prefix_dir"/lib/libavutil-kmb.so* "$output_dir/lib/"
fi

if [[ "$runtime_profile" == "subtitle-sdr" ]]; then
    mkdir -p "$output_dir/compliance/subtitle-components"
    cp -R "$subtitle_prefix_dir/compliance/." "$output_dir/compliance/subtitle-components/"
    cp "$root_dir/compliance/subtitles/manifest.json" "$output_dir/compliance/subtitle-components-manifest.json"
fi

cp "$root_dir/native/include/kmedia_bridge.h" "$output_dir/include/kmedia_bridge.h"
cp "$source_archive" "$output_dir/compliance/source/$source_name"
cp "$source_dir/COPYING.LGPLv2.1" "$output_dir/compliance/COPYING.LGPLv2.1"
cp "$source_dir/LICENSE.md" "$output_dir/compliance/FFMPEG_LICENSE.md"
cp "$source_dir/config.h" "$output_dir/compliance/ffmpeg-config.h"
cp "$source_dir/config_components.h" "$output_dir/compliance/ffmpeg-config-components.h"
printf '%s\n' "${configure_arguments[@]}" > "$output_dir/compliance/ffmpeg-configure-arguments.txt"
printf '%s\n' "$runtime_profile" > "$output_dir/compliance/runtime-profile.txt"

mkdir -p \
    "$output_dir/compliance/kmediabridge-source/native/include" \
    "$output_dir/compliance/kmediabridge-source/native/src" \
    "$output_dir/compliance/kmediabridge-source/docs" \
    "$output_dir/compliance/kmediabridge-source/scripts"
cp "$root_dir/LICENSES/LGPL-2.1-or-later.txt" "$output_dir/compliance/kmediabridge-source/LICENSE"
cp \
    "$root_dir/ffmpeg-runtime-desktop/src/main/resources/META-INF/NOTICE" \
    "$output_dir/compliance/kmediabridge-source/NOTICE"
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
cp "$root_dir/native/src/kmedia_bridge_tonemap.c" "$output_dir/compliance/kmediabridge-source/native/src/kmedia_bridge_tonemap.c"
cp "$root_dir/native/src/kmedia_bridge_hdr_math.c" "$output_dir/compliance/kmediabridge-source/native/src/kmedia_bridge_hdr_math.c"
cp "$root_dir/native/src/kmedia_bridge_hdr_math.h" "$output_dir/compliance/kmediabridge-source/native/src/kmedia_bridge_hdr_math.h"
cp "$root_dir/native/src/kmedia_bridge_timestamps.h" "$output_dir/compliance/kmediabridge-source/native/src/kmedia_bridge_timestamps.h"
mkdir -p "$output_dir/compliance/kmediabridge-source/native/tests"
cp "$root_dir/native/tests/kmedia_bridge_timestamps_test.c" "$output_dir/compliance/kmediabridge-source/native/tests/kmedia_bridge_timestamps_test.c"
cp "$root_dir/native/tests/kmedia_bridge_hdr_math_test.c" "$output_dir/compliance/kmediabridge-source/native/tests/kmedia_bridge_hdr_math_test.c"
cp "$root_dir/scripts/generate_native_checksums.py" "$output_dir/compliance/kmediabridge-source/scripts/generate_native_checksums.py"
cp "$root_dir/scripts/inspect_native_runtime.py" "$output_dir/compliance/kmediabridge-source/scripts/inspect_native_runtime.py"
cp "$root_dir/scripts/package_native_runtime.py" "$output_dir/compliance/kmediabridge-source/scripts/package_native_runtime.py"
cp "$root_dir/scripts/assemble_runtime_manifest.py" "$output_dir/compliance/kmediabridge-source/scripts/assemble_runtime_manifest.py"
cp "$root_dir/scripts/verify_ffmpeg_release.sh" "$output_dir/compliance/kmediabridge-source/scripts/verify_ffmpeg_release.sh"

"${CC:-cc}" --version > "$output_dir/compliance/compiler-version.txt"
if git -C "$root_dir" rev-parse --verify HEAD >/dev/null 2>&1 &&
    git -C "$root_dir" diff-index --quiet HEAD -- &&
    [[ -z "$(git -C "$root_dir" ls-files --others --exclude-standard)" ]]; then
    git -C "$root_dir" rev-parse HEAD > "$output_dir/compliance/build-recipe-revision.txt"
else
    printf '%s\n' "uncommitted-source" > "$output_dir/compliance/build-recipe-revision.txt"
fi

python3 "$root_dir/scripts/inspect_native_runtime.py" \
    --library "$output_dir/lib/libkmediabridge.dylib" \
    --fallback-library "$output_dir/lib/libkmediabridge.so" \
    --source-sha256 "$source_sha256" \
    --output "$output_dir/compliance/runtime-inspection.json"

python3 "$root_dir/scripts/generate_native_checksums.py" \
    --root "$output_dir" \
    --output "$output_dir/compliance/SHA256SUMS"

echo "LGPL native bridge staged in $output_dir"
