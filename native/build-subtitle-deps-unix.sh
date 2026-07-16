#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
work_dir_argument="${1:-$root_dir/build/subtitle-deps-work}"
prefix_dir_argument="${2:-$root_dir/build/subtitle-deps-prefix}"
mkdir -p "$work_dir_argument" "$prefix_dir_argument"
work_dir="$(cd "$work_dir_argument" && pwd)"
prefix_dir="$(cd "$prefix_dir_argument" && pwd)"

freetype_version="2.14.3"
freetype_url="https://downloads.sourceforge.net/project/freetype/freetype2/2.14.3/freetype-2.14.3.tar.xz"
freetype_sha256="36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f"
fribidi_version="1.0.16"
fribidi_url="https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz"
fribidi_sha256="1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c"
harfbuzz_version="14.2.1"
harfbuzz_url="https://github.com/harfbuzz/harfbuzz/releases/download/14.2.1/harfbuzz-14.2.1.tar.xz"
harfbuzz_sha256="a54a5d8e9380a41fbb762ce367bcbf7704792dfca0d93f1bbca86c5a57902e0e"
libunibreak_version="7.0"
libunibreak_url="https://github.com/adah1972/libunibreak/releases/download/libunibreak_7_0/libunibreak-7.0.tar.gz"
libunibreak_sha256="8c9a6e121736cd0d5c890ae3ae96f3f4010a19aa040f1dbded833a62a87717d3"
libass_version="0.17.5"
libass_url="https://github.com/libass/libass/releases/download/0.17.5/libass-0.17.5.tar.xz"
libass_sha256="2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc"

for tool in meson ninja pkg-config make "${CC:-cc}" "${CXX:-c++}"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Required subtitle dependency build tool is missing: $tool" >&2
        exit 1
    fi
done

download_and_verify() {
    local name="$1"
    local url="$2"
    local expected_sha256="$3"
    local archive="$work_dir/$name"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --proto '=https' --tlsv1.2 --output "$archive" "$url"
    fi
    local actual_sha256
    if command -v shasum >/dev/null 2>&1; then
        actual_sha256="$(shasum -a 256 "$archive" | awk '{print $1}')"
    else
        actual_sha256="$(sha256sum "$archive" | awk '{print $1}')"
    fi
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
        echo "$name source SHA-256 mismatch." >&2
        exit 1
    fi
}

download_and_verify "freetype-$freetype_version.tar.xz" "$freetype_url" "$freetype_sha256"
download_and_verify "fribidi-$fribidi_version.tar.xz" "$fribidi_url" "$fribidi_sha256"
download_and_verify "harfbuzz-$harfbuzz_version.tar.xz" "$harfbuzz_url" "$harfbuzz_sha256"
download_and_verify "libunibreak-$libunibreak_version.tar.gz" "$libunibreak_url" "$libunibreak_sha256"
download_and_verify "libass-$libass_version.tar.xz" "$libass_url" "$libass_sha256"

rm -rf \
    "$work_dir/freetype-$freetype_version" \
    "$work_dir/fribidi-$fribidi_version" \
    "$work_dir/harfbuzz-$harfbuzz_version" \
    "$work_dir/libunibreak-$libunibreak_version" \
    "$work_dir/libass-$libass_version" \
    "$work_dir/freetype-build" \
    "$work_dir/fribidi-build" \
    "$work_dir/harfbuzz-build" \
    "$work_dir/libunibreak-build" \
    "$work_dir/libass-build"
rm -rf "$prefix_dir/include" "$prefix_dir/lib" "$prefix_dir/share"

tar -C "$work_dir" -xf "$work_dir/freetype-$freetype_version.tar.xz"
tar -C "$work_dir" -xf "$work_dir/fribidi-$fribidi_version.tar.xz"
tar -C "$work_dir" -xf "$work_dir/harfbuzz-$harfbuzz_version.tar.xz"
tar -C "$work_dir" -xf "$work_dir/libunibreak-$libunibreak_version.tar.gz"
tar -C "$work_dir" -xf "$work_dir/libass-$libass_version.tar.xz"

common_meson_arguments=(
    "--prefix=$prefix_dir"
    "--libdir=lib"
    "--buildtype=release"
    "--default-library=static"
    "-Db_staticpic=true"
)

meson setup "$work_dir/freetype-build" "$work_dir/freetype-$freetype_version" \
    "${common_meson_arguments[@]}" \
    -Dharfbuzz=disabled \
    -Dpng=disabled \
    -Dbzip2=disabled \
    -Dbrotli=disabled \
    -Dzlib=disabled \
    -Dtests=disabled
meson compile -C "$work_dir/freetype-build"
meson install -C "$work_dir/freetype-build"

meson setup "$work_dir/fribidi-build" "$work_dir/fribidi-$fribidi_version" \
    "${common_meson_arguments[@]}" \
    -Ddeprecated=false \
    -Ddocs=false \
    -Dbin=false \
    -Dtests=false
meson compile -C "$work_dir/fribidi-build"
meson install -C "$work_dir/fribidi-build"

PKG_CONFIG_PATH="$prefix_dir/lib/pkgconfig" \
meson setup "$work_dir/harfbuzz-build" "$work_dir/harfbuzz-$harfbuzz_version" \
    "${common_meson_arguments[@]}" \
    -Dglib=disabled \
    -Dgobject=disabled \
    -Dcairo=disabled \
    -Dchafa=disabled \
    -Dpng=disabled \
    -Dzlib=disabled \
    -Dicu=disabled \
    -Dgraphite2=disabled \
    -Dfreetype=enabled \
    -Dcoretext=disabled \
    -Ddirectwrite=disabled \
    -Draster=disabled \
    -Dvector=disabled \
    -Dgpu=disabled \
    -Dsubset=disabled \
    -Dtests=disabled \
    -Dintrospection=disabled \
    -Ddocs=disabled \
    -Dutilities=disabled
meson compile -C "$work_dir/harfbuzz-build"
meson install -C "$work_dir/harfbuzz-build"

libunibreak_configure_arguments=(
    "--prefix=$prefix_dir"
    "--libdir=$prefix_dir/lib"
    "--disable-shared"
    "--enable-static"
    "--enable-pic"
)
(
    cd "$work_dir/libunibreak-$libunibreak_version"
    ./configure "${libunibreak_configure_arguments[@]}"
    make -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
    make install
)

libass_configure_arguments=(
    "--prefix=$prefix_dir"
    "--libdir=$prefix_dir/lib"
    "--disable-shared"
    "--enable-static"
    "--disable-fontconfig"
    "--enable-libunibreak"
    "--disable-asm"
)
if [[ "$(uname -s)" != "Darwin" ]]; then
    libass_configure_arguments+=("--disable-coretext")
fi
(
    cd "$work_dir/libass-$libass_version"
    PKG_CONFIG_PATH="$prefix_dir/lib/pkgconfig" \
        ./configure "${libass_configure_arguments[@]}"
    make -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
    make install
)

mkdir -p "$prefix_dir/compliance/source" "$prefix_dir/compliance/licenses"
cp "$work_dir/freetype-$freetype_version.tar.xz" "$prefix_dir/compliance/source/"
cp "$work_dir/fribidi-$fribidi_version.tar.xz" "$prefix_dir/compliance/source/"
cp "$work_dir/harfbuzz-$harfbuzz_version.tar.xz" "$prefix_dir/compliance/source/"
cp "$work_dir/libunibreak-$libunibreak_version.tar.gz" "$prefix_dir/compliance/source/"
cp "$work_dir/libass-$libass_version.tar.xz" "$prefix_dir/compliance/source/"
cp "$work_dir/freetype-$freetype_version/LICENSE.TXT" "$prefix_dir/compliance/licenses/FREETYPE-LICENSE.TXT"
cp "$work_dir/fribidi-$fribidi_version/COPYING" "$prefix_dir/compliance/licenses/FRIBIDI-COPYING"
cp "$work_dir/harfbuzz-$harfbuzz_version/COPYING" "$prefix_dir/compliance/licenses/HARFBUZZ-COPYING"
cp "$work_dir/libunibreak-$libunibreak_version/LICENCE" "$prefix_dir/compliance/licenses/LIBUNIBREAK-LICENCE"
cp "$work_dir/libass-$libass_version/COPYING" "$prefix_dir/compliance/licenses/LIBASS-COPYING"
printf '%s\n' "${libunibreak_configure_arguments[@]}" > "$prefix_dir/compliance/libunibreak-configure-arguments.txt"
printf '%s\n' "${libass_configure_arguments[@]}" > "$prefix_dir/compliance/libass-configure-arguments.txt"

echo "Static PIC subtitle dependencies staged in $prefix_dir"
