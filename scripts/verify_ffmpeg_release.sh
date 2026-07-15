#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

set -euo pipefail

archive="${1:?FFmpeg source archive path is required}"
signature="${2:?FFmpeg source signature path is required}"
public_key="${3:?FFmpeg release public key path is required}"
expected_fingerprint="FCF986EA15E6E293A5644F10B4322F04D67658D8"

for path in "$archive" "$signature" "$public_key"; do
    if [[ ! -f "$path" ]]; then
        echo "Release verification input is missing." >&2
        exit 1
    fi
done
if ! command -v gpg >/dev/null 2>&1; then
    echo "GnuPG is required to verify the FFmpeg release signature." >&2
    exit 1
fi

actual_fingerprints="$(gpg --batch --show-keys --with-colons "$public_key" | awk -F: '$1 == "fpr" { print $10 }')"
if ! grep -Fxq "$expected_fingerprint" <<< "$actual_fingerprints"; then
    echo "The FFmpeg release key fingerprint does not match the pinned fingerprint." >&2
    exit 1
fi

keyring="$(mktemp -d)"
trap 'rm -rf "$keyring"' EXIT
chmod 700 "$keyring"
gpg --batch --homedir "$keyring" --import "$public_key" >/dev/null 2>&1
gpg --batch --homedir "$keyring" --verify "$signature" "$archive"
echo "Verified FFmpeg release signature with $expected_fingerprint"
