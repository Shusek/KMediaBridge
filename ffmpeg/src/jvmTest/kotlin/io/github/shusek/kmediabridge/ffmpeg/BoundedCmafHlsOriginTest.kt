// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaFragment
import io.github.shusek.kmediabridge.MediaOutputInfo
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoHandling
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedCmafHlsOriginTest {
    @Test
    fun servesAPlayableBoundedCmafWindowWithByteRanges() =
        runBlocking {
            val origin =
                BoundedCmafHlsOrigin(
                    session = FakeSession(events()),
                    maxBufferedFragments = 3,
                    maxBufferedBytes = 1024L,
                )
            try {
                val output = origin.startAndAwaitReady()
                assertEquals(4, output.selectedVideoTrackId)
                assertTrue(origin.playlistUrl.startsWith("http://127.0.0.1:"))

                val playlist = awaitEndedPlaylist(origin.playlistUrl)
                assertTrue("#EXT-X-MAP:URI=\"init.mp4\"" in playlist)
                assertTrue("#EXT-X-ENDLIST" in playlist)
                assertFalse("segment-1.m4s" in playlist)
                assertTrue("segment-2.m4s" in playlist)
                assertTrue("segment-4.m4s" in playlist)

                assertContentEquals("init".encodeToByteArray(), readBytes(origin.playlistUrl, "init.mp4"))
                assertContentEquals(
                    "rag".encodeToByteArray(),
                    readBytes(origin.playlistUrl, "segment-4.m4s", range = "bytes=1-3"),
                )
            } finally {
                origin.closeAsync()
                origin.closeAsync()
            }
        }

    @Test
    fun failsImmediatelyWhenTheBridgeEndsWithoutPlayableMedia() {
        runBlocking {
            val origin =
                BoundedCmafHlsOrigin(
                    session = FakeSession(flow { emit(MediaBridgeEvent.EndOfStream) }),
                    maxBufferedFragments = 3,
                    maxBufferedBytes = 1024L,
                )
            try {
                assertFailsWith<io.github.shusek.kmediabridge.MediaBridgeException> {
                    origin.startAndAwaitReady()
                }
            } finally {
                origin.closeAsync()
            }
        }
    }

    private suspend fun awaitEndedPlaylist(url: String): String {
        repeat(100) {
            val playlist = URI(url).toURL().readText()
            if ("#EXT-X-ENDLIST" in playlist) return playlist
            delay(10L)
        }
        error("The test CMAF origin did not finish.")
    }

    private fun readBytes(
        playlistUrl: String,
        relativePath: String,
        range: String? = null,
    ): ByteArray {
        val connection = URI(playlistUrl).resolve(relativePath).toURL().openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        try {
            assertEquals(if (range == null) 200 else 206, connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun events(): Flow<MediaBridgeEvent> =
        flow {
            emit(MediaBridgeEvent.OutputConfigured(OUTPUT_INFO))
            emit(MediaBridgeEvent.Fragment(fragment(0L, true, "init")))
            repeat(4) { index -> emit(MediaBridgeEvent.Fragment(fragment(index + 1L, false, "frag"))) }
            emit(MediaBridgeEvent.EndOfStream)
        }

    private fun fragment(
        sequence: Long,
        initialization: Boolean,
        value: String,
    ): MediaFragment =
        MediaFragment(
            sequence = sequence,
            presentationTimeUs = (sequence - 1L).coerceAtLeast(0L) * 2_000_000L,
            durationUs = if (initialization) 0L else 2_000_000L,
            isInitialization = initialization,
            bytes = value.encodeToByteArray(),
        )

    private class FakeSession(
        override val events: Flow<MediaBridgeEvent>,
    ) : MediaBridgeSession {
        override suspend fun seekTo(positionUs: Long): Unit = Unit

        override suspend fun close(): Unit = Unit
    }

    private companion object {
        val OUTPUT_INFO =
            MediaOutputInfo(
                videoHandling = VideoHandling.COPY,
                audioHandling = AudioHandling.COPY,
                subtitleHandling = SubtitleHandling.OMIT,
                selectedVideoTrackId = 4,
                selectedAudioTrackId = 5,
                selectedSubtitleTrackId = null,
                inputColorInfo =
                    VideoColorInfo(
                        dynamicRange = DynamicRangeFormat.SDR,
                        bitDepth = 8,
                        range = ColorRange.LIMITED,
                        primaries = ColorPrimaries.BT709,
                        transfer = ColorTransfer.BT709,
                        matrix = ColorMatrix.BT709,
                    ),
                outputColorInfo = null,
            )
    }
}
