#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Exercise the native ABI without exposing an input locator in output."""

from __future__ import annotations

import argparse
import ctypes
import json
import sys
from pathlib import Path


def decode_and_free(library: ctypes.CDLL, pointer: ctypes.c_void_p) -> str:
    if not pointer.value:
        return ""
    try:
        return ctypes.string_at(pointer.value).decode("utf-8")
    finally:
        library.kmb_free_string(pointer)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()

    library = ctypes.CDLL(str(arguments.library.resolve()))
    library.kmb_probe_json.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p)]
    library.kmb_probe_json.restype = ctypes.c_int
    library.kmb_remux_fragmented_mp4.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    library.kmb_remux_fragmented_mp4.restype = ctypes.c_int
    library.kmb_free_string.argtypes = [ctypes.c_void_p]

    input_bytes = str(arguments.input.resolve()).encode("utf-8")
    probe_pointer = ctypes.c_void_p()
    error_pointer = ctypes.c_void_p()
    probe_result = library.kmb_probe_json(input_bytes, ctypes.byref(probe_pointer), ctypes.byref(error_pointer))
    probe_error = decode_and_free(library, error_pointer)
    if probe_result != 0:
        print(f"native smoke error: probe failed without exposing the locator: {probe_error}", file=sys.stderr)
        return 1

    probe_text = decode_and_free(library, probe_pointer)
    if input_bytes.decode("utf-8") in probe_text:
        print("native smoke error: probe response exposed the input locator", file=sys.stderr)
        return 1
    probe = json.loads(probe_text)
    if not probe.get("tracks"):
        print("native smoke error: probe returned no audio/video tracks", file=sys.stderr)
        return 1

    arguments.output.unlink(missing_ok=True)
    error_pointer = ctypes.c_void_p()
    remux_result = library.kmb_remux_fragmented_mp4(
        input_bytes,
        str(arguments.output.resolve()).encode("utf-8"),
        ctypes.byref(error_pointer),
    )
    remux_error = decode_and_free(library, error_pointer)
    if remux_result != 0:
        print(f"native smoke error: remux failed without exposing the locator: {remux_error}", file=sys.stderr)
        return 1
    if not arguments.output.is_file() or arguments.output.stat().st_size == 0:
        print("native smoke error: remux produced no output", file=sys.stderr)
        return 1

    print(f"Native smoke test passed with {len(probe['tracks'])} track(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
