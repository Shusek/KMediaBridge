// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.BridgeCapabilities
import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.BridgeSupport
import io.github.shusek.kmediabridge.MediaBridge
import io.github.shusek.kmediabridge.MediaBridgeProvider
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaProbe

/**
 * Narrow SPI implemented by platform-native payload artifacts.
 *
 * Implementations must link FFmpeg dynamically. Launching an `ffmpeg` executable is intentionally outside this API.
 */
public interface FfmpegNativeDriver {
    public val runtimeInfo: FfmpegRuntimeInfo
    public val capabilities: BridgeCapabilities

    public suspend fun evaluate(
        input: MediaInput,
        request: BridgeRequest,
    ): BridgeSupport

    public suspend fun probe(input: MediaInput): MediaProbe

    public suspend fun open(
        input: MediaInput,
        request: BridgeRequest,
    ): MediaBridgeSession
}

public class FfmpegMediaBridge private constructor(
    private val driver: FfmpegNativeDriver,
) : MediaBridge {
    override val id: String = "ffmpeg"
    override val capabilities: BridgeCapabilities = driver.capabilities

    override suspend fun probe(input: MediaInput): MediaProbe = driver.probe(input)

    override suspend fun open(
        input: MediaInput,
        request: BridgeRequest,
    ): MediaBridgeSession = driver.open(input, request)

    public companion object {
        public fun create(driver: FfmpegNativeDriver): FfmpegMediaBridge {
            FfmpegComplianceVerifier.requireAllowedByDistributionPolicy(driver.runtimeInfo)
            return FfmpegMediaBridge(driver)
        }
    }
}

public class FfmpegMediaBridgeProvider(
    private val driver: FfmpegNativeDriver,
    override val priority: Int = 50,
) : MediaBridgeProvider {
    override val id: String = "ffmpeg"

    init {
        FfmpegComplianceVerifier.requireAllowedByDistributionPolicy(driver.runtimeInfo)
    }

    override suspend fun evaluate(
        input: MediaInput,
        request: BridgeRequest,
    ): BridgeSupport = driver.evaluate(input, request)

    override fun create(): MediaBridge = FfmpegMediaBridge.create(driver)
}
