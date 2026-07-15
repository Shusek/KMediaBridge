/* SPDX-License-Identifier: LGPL-2.1-or-later */

#ifndef KMEDIA_BRIDGE_H
#define KMEDIA_BRIDGE_H

#include <stdint.h>

#if defined(_WIN32)
#define KMB_EXPORT __declspec(dllexport)
#else
#define KMB_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KMB_ABI_VERSION 1

typedef enum KmbResult {
    KMB_OK = 0,
    KMB_INVALID_ARGUMENT = 1,
    KMB_OPEN_INPUT_FAILED = 2,
    KMB_STREAM_INFO_FAILED = 3,
    KMB_ALLOCATION_FAILED = 4,
    KMB_OPEN_OUTPUT_FAILED = 5,
    KMB_WRITE_FAILED = 6,
    KMB_READ_FAILED = 7,
    KMB_UNSUPPORTED = 8
} KmbResult;

KMB_EXPORT uint32_t kmb_abi_version(void);
KMB_EXPORT const char *kmb_ffmpeg_version(void);
KMB_EXPORT const char *kmb_ffmpeg_license(void);
KMB_EXPORT const char *kmb_ffmpeg_configuration(void);

/*
 * Returns a newly allocated UTF-8 JSON document in output_json.
 * The input locator is deliberately excluded from the response and from errors.
 */
KMB_EXPORT KmbResult kmb_probe_json(const char *input_locator, char **output_json, char **output_error);

/* Copies supported audio/video streams into fragmented MP4 without re-encoding the video picture. */
KMB_EXPORT KmbResult kmb_remux_fragmented_mp4(
    const char *input_locator,
    const char *output_path,
    char **output_error
);

KMB_EXPORT void kmb_free_string(char *value);

#ifdef __cplusplus
}
#endif

#endif
