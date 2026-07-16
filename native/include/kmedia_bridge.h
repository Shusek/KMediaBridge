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

#define KMB_ABI_VERSION 4

typedef enum KmbResult {
    KMB_OK = 0,
    KMB_INVALID_ARGUMENT = 1,
    KMB_OPEN_INPUT_FAILED = 2,
    KMB_STREAM_INFO_FAILED = 3,
    KMB_ALLOCATION_FAILED = 4,
    KMB_OPEN_OUTPUT_FAILED = 5,
    KMB_WRITE_FAILED = 6,
    KMB_READ_FAILED = 7,
    KMB_UNSUPPORTED = 8,
    KMB_CANCELLED = 9
} KmbResult;

/* Return zero to continue or a non-zero value to cancel the remux operation. */
typedef int32_t (*KmbWriteCallback)(void *opaque, const uint8_t *bytes, int32_t size);

KMB_EXPORT uint32_t kmb_abi_version(void);
KMB_EXPORT const char *kmb_ffmpeg_version(void);
KMB_EXPORT const char *kmb_ffmpeg_license(void);
KMB_EXPORT const char *kmb_ffmpeg_configuration(void);
KMB_EXPORT const char *kmb_runtime_features_json(void);

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

/*
 * Streams fragmented MP4 through a bounded caller callback. The callback may
 * apply backpressure by blocking and may cancel by returning a non-zero value.
 * A positive start_time_us seeks to the preceding usable keyframe.
 */
KMB_EXPORT KmbResult kmb_remux_fragmented_mp4_stream(
    const char *input_locator,
    int64_t fragment_duration_us,
    int64_t start_time_us,
    int32_t preferred_video_track_id,
    int32_t preferred_audio_track_id,
    KmbWriteCallback write_callback,
    void *opaque,
    char **output_error
);

/*
 * Decodes one SDR video track, composites one text subtitle track through
 * libass, encodes AVC, and streams fragmented MP4. Runtimes that do not carry
 * this optional pipeline return KMB_UNSUPPORTED and advertise the same fact in
 * manifest.properties. HDR/HLG/Dolby Vision input is rejected by design.
 */
KMB_EXPORT KmbResult kmb_burn_subtitles_fragmented_mp4_stream(
    const char *input_locator,
    int64_t fragment_duration_us,
    int64_t start_time_us,
    int32_t preferred_video_track_id,
    int32_t preferred_audio_track_id,
    int32_t preferred_subtitle_track_id,
    KmbWriteCallback write_callback,
    void *opaque,
    char **output_error
);

KMB_EXPORT void kmb_free_string(char *value);

#ifdef __cplusplus
}
#endif

#endif
