// SPDX-License-Identifier: LGPL-2.1-or-later

package io.github.shusek.kmediabridge

import kotlinx.coroutines.flow.Flow

public data class BridgeCapabilities(
    public val inputContainers: Set<MediaContainer>,
    public val outputs: Set<BridgeOutput>,
    public val canProbe: Boolean,
    public val canCopyVideo: Boolean,
    public val canToneMapToSdr: Boolean,
    public val canConvertDolbyVisionProfile7: Boolean,
    public val supportsLiveInput: Boolean,
    public val supportsEncryptedInput: Boolean,
    public val supportsRemoteInput: Boolean = false,
    public val canTranscodeVideo: Boolean = false,
    public val canTranscodeAudio: Boolean = false,
    public val canBurnSubtitles: Boolean = false,
)

public interface MediaBridge {
    public val id: String
    public val capabilities: BridgeCapabilities

    public suspend fun probe(input: MediaInput): MediaProbe

    public suspend fun open(
        input: MediaInput,
        request: BridgeRequest,
    ): MediaBridgeSession
}

public interface MediaBridgeSession {
    public val events: Flow<MediaBridgeEvent>

    public suspend fun seekTo(positionUs: Long)

    public suspend fun close()
}

public sealed interface BridgeSupport {
    public data class Supported(
        public val confidence: Int,
        public val reason: String,
    ) : BridgeSupport {
        init {
            require(confidence in 0..100) { "Confidence must be between 0 and 100." }
        }
    }

    public data class Unsupported(
        public val reason: String,
    ) : BridgeSupport
}

public interface MediaBridgeProvider {
    public val id: String
    public val priority: Int

    public suspend fun evaluate(
        input: MediaInput,
        request: BridgeRequest,
    ): BridgeSupport

    public fun create(): MediaBridge
}

public data class MediaBridgeSelection(
    public val providerId: String,
    public val support: BridgeSupport.Supported,
    public val bridge: MediaBridge,
)

public class MediaBridgeRegistry(
    providers: Iterable<MediaBridgeProvider>,
) {
    private val providers: List<MediaBridgeProvider> =
        providers
            .toList()
            .also { require(it.map(MediaBridgeProvider::id).distinct().size == it.size) { "Provider IDs must be unique." } }
            .sortedWith(
                compareByDescending<MediaBridgeProvider> { it.priority }
                    .thenBy { it.id },
            )

    public suspend fun select(
        input: MediaInput,
        request: BridgeRequest,
    ): MediaBridgeSelection {
        val failures = mutableListOf<String>()
        for (provider in providers) {
            when (val support = provider.evaluate(input, request)) {
                is BridgeSupport.Supported ->
                    return MediaBridgeSelection(
                        providerId = provider.id,
                        support = support,
                        bridge = provider.create(),
                    )
                is BridgeSupport.Unsupported -> failures += "${provider.id}: ${support.reason}"
            }
        }

        throw MediaBridgeException(
            code = MediaBridgeErrorCode.NO_SUPPORTED_BACKEND,
            message = failures.joinToString(prefix = "No media bridge accepted the request. ", separator = "; "),
        )
    }
}

public enum class MediaBridgeErrorCode {
    NO_SUPPORTED_BACKEND,
    UNSUPPORTED_INPUT,
    UNSUPPORTED_REQUEST,
    NON_COMPLIANT_NATIVE_RUNTIME,
    PROBE_FAILED,
    CONVERSION_FAILED,
    CANCELLED,
    INTERNAL,
}

public class MediaBridgeException(
    public val code: MediaBridgeErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
