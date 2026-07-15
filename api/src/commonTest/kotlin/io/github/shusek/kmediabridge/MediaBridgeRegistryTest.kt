// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MediaBridgeRegistryTest {
    @Test
    fun selectsTheHighestPrioritySupportedProviderDeterministically() =
        runTest {
            val registry =
                MediaBridgeRegistry(
                    listOf(
                        provider("fallback", priority = 1, supported = true),
                        provider("native", priority = 100, supported = true),
                    ),
                )

            val result = registry.select(MediaInput("movie.mkv", MediaInputKind.FILE), BridgeRequest())

            assertEquals("native", result.providerId)
            assertEquals("native", result.bridge.id)
        }

    @Test
    fun reportsEveryRejectedProviderWithoutExposingTheInputLocator() =
        runTest {
            val secretLikeLocator = "https://example.invalid/video?token=do-not-log"
            val registry = MediaBridgeRegistry(listOf(provider("one", 2, false), provider("two", 1, false)))

            val failure =
                assertFailsWith<MediaBridgeException> {
                    registry.select(MediaInput(secretLikeLocator), BridgeRequest())
                }

            assertEquals(MediaBridgeErrorCode.NO_SUPPORTED_BACKEND, failure.code)
            assertFalse(failure.message.orEmpty().contains("do-not-log"))
        }

    @Test
    fun mediaInputStringRepresentationRedactsItsLocator() {
        val input = MediaInput("https://example.invalid/video?token=do-not-log")

        assertFalse(input.toString().contains("do-not-log"))
    }

    private fun provider(
        id: String,
        priority: Int,
        supported: Boolean,
    ): MediaBridgeProvider =
        object : MediaBridgeProvider {
            override val id: String = id
            override val priority: Int = priority

            override suspend fun evaluate(
                input: MediaInput,
                request: BridgeRequest,
            ): BridgeSupport =
                if (supported) {
                    BridgeSupport.Supported(confidence = 100, reason = "test")
                } else {
                    BridgeSupport.Unsupported("test rejection")
                }

            override fun create(): MediaBridge = testBridge(id)
        }

    private fun testBridge(id: String): MediaBridge =
        object : MediaBridge {
            override val id: String = id
            override val capabilities: BridgeCapabilities =
                BridgeCapabilities(
                    inputContainers = setOf(MediaContainer.MATROSKA),
                    outputs = setOf(BridgeOutput.CMAF_FRAGMENT_STREAM),
                    canProbe = true,
                    canCopyVideo = true,
                    canToneMapToSdr = false,
                    canConvertDolbyVisionProfile7 = false,
                    supportsLiveInput = false,
                    supportsEncryptedInput = false,
                )

            override suspend fun probe(input: MediaInput): MediaProbe =
                MediaProbe(MediaContainer.MATROSKA, durationUs = null, tracks = emptyList())

            override suspend fun open(
                input: MediaInput,
                request: BridgeRequest,
            ): MediaBridgeSession =
                object : MediaBridgeSession {
                    override val events = emptyFlow<MediaBridgeEvent>()

                    override suspend fun seekTo(positionUs: Long) = Unit

                    override suspend fun close() = Unit
                }
        }
}
