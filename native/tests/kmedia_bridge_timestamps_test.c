/* SPDX-License-Identifier: LGPL-2.1-or-later */

#include "kmedia_bridge_timestamps.h"

#include <assert.h>
#include <stdio.h>

static void test_reordered_video_without_dts(void) {
    AVCodecParameters parameters = {0};
    AVStream stream = {0};
    KmbTimestampState state = {0};
    AVPacket first = {0};
    AVPacket second = {0};
    AVPacket third = {0};

    parameters.codec_type = AVMEDIA_TYPE_VIDEO;
    parameters.video_delay = 2;
    stream.codecpar = &parameters;
    stream.time_base = (AVRational){1, 1000};
    stream.avg_frame_rate = (AVRational){30, 1};

    first.pts = 0;
    first.dts = AV_NOPTS_VALUE;
    first.duration = 33;
    second.pts = 133;
    second.dts = AV_NOPTS_VALUE;
    second.duration = 33;
    third.pts = 67;
    third.dts = AV_NOPTS_VALUE;
    third.duration = 33;

    kmb_prepare_packet_timestamps(&stream, &first, &state);
    kmb_prepare_packet_timestamps(&stream, &second, &state);
    kmb_prepare_packet_timestamps(&stream, &third, &state);

    assert(first.dts < 0);
    assert(first.dts < second.dts);
    assert(second.dts < third.dts);
    assert(first.pts == 0);
    assert(second.pts == 133);
    assert(third.pts == 67);
}

static void test_audio_preserves_negative_start_and_fills_missing_values(void) {
    AVCodecParameters parameters = {0};
    AVStream stream = {0};
    KmbTimestampState state = {0};
    AVPacket first = {0};
    AVPacket second = {0};

    parameters.codec_type = AVMEDIA_TYPE_AUDIO;
    parameters.sample_rate = 48000;
    stream.codecpar = &parameters;
    stream.time_base = (AVRational){1, 1000};

    first.pts = -7;
    first.dts = -7;
    first.duration = 20;
    second.pts = AV_NOPTS_VALUE;
    second.dts = AV_NOPTS_VALUE;
    second.duration = 20;

    kmb_prepare_packet_timestamps(&stream, &first, &state);
    kmb_prepare_packet_timestamps(&stream, &second, &state);

    assert(first.dts == -7);
    assert(second.dts == 13);
    assert(second.pts == second.dts);
}

int main(void) {
    test_reordered_video_without_dts();
    test_audio_preserves_negative_start_and_fills_missing_values();
    puts("KMEDIA_BRIDGE_TIMESTAMP_TEST_OK");
    return 0;
}
