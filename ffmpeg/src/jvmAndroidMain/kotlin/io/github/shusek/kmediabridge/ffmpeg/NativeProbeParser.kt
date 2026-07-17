// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.AudioTrackInfo
import io.github.shusek.kmediabridge.Chromaticity
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.ContentLightLevelInfo
import io.github.shusek.kmediabridge.DolbyVisionInfo
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MasteringDisplayInfo
import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediabridge.MediaContainer
import io.github.shusek.kmediabridge.MediaProbe
import io.github.shusek.kmediabridge.MediaTrackInfo
import io.github.shusek.kmediabridge.SubtitleTrackInfo
import io.github.shusek.kmediabridge.VideoCodec
import io.github.shusek.kmediabridge.VideoColorInfo
import io.github.shusek.kmediabridge.VideoTrackInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object NativeProbeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(document: String): MediaProbe {
        try {
            val root = json.parseToJsonElement(document).jsonObject
            val tracks = root.getValue("tracks").jsonArray.mapNotNull(::parseTrack)
            return MediaProbe(
                container = parseContainer(root.string("format")),
                durationUs = root.nullableLong("durationUs")?.takeIf { it >= 0L },
                tracks = tracks,
            )
        } catch (error: Exception) {
            throw MediaBridgeException(
                MediaBridgeErrorCode.PROBE_FAILED,
                "The native FFmpeg runtime returned an invalid typed probe document.",
                error,
            )
        }
    }

    private fun parseTrack(element: JsonElement): MediaTrackInfo? {
        val track = element.jsonObject
        return when (track.string("type")) {
            "video" -> parseVideo(track)
            "audio" ->
                AudioTrackInfo(
                    id = track.int("id"),
                    language = track.optionalString("language"),
                    codecName = track.string("codec"),
                    channels = track.optionalPositiveInt("channels"),
                    sampleRateHz = track.optionalPositiveInt("sampleRateHz"),
                    bitrate = track.optionalPositiveInt("bitrate"),
                    title = track.optionalString("title"),
                    isDefault = track.boolean("isDefault"),
                )
            "subtitle" ->
                SubtitleTrackInfo(
                    id = track.int("id"),
                    language = track.optionalString("language"),
                    codecName = track.string("codec"),
                    isImageBased = track.boolean("isImageBased"),
                    title = track.optionalString("title"),
                    isDefault = track.boolean("isDefault"),
                )
            else -> null
        }
    }

    private fun parseVideo(track: JsonObject): VideoTrackInfo {
        val dynamicRange =
            runCatching { DynamicRangeFormat.valueOf(track.string("dynamicRange")) }
                .getOrDefault(DynamicRangeFormat.UNKNOWN)
        val dolbyVision =
            track["dolbyVision"]
                ?.takeUnless { it is JsonNull }
                ?.jsonObject
                ?.let { dovi ->
                    DolbyVisionInfo(
                        profile = dovi.int("profile"),
                        level = dovi.optionalPositiveInt("level"),
                        hasRpu = dovi.boolean("hasRpu"),
                        hasEnhancementLayer = dovi.boolean("hasEnhancementLayer"),
                    )
                }
        val frameRateDenominator = track.optionalPositiveInt("frameRateDenominator")
        val frameRateNumerator = track.optionalPositiveInt("frameRateNumerator")
        return VideoTrackInfo(
            id = track.int("id"),
            language = track.optionalString("language"),
            codec = parseVideoCodec(track.string("codec"), dynamicRange),
            profile = track.optionalNonNegativeInt("profile"),
            level = track.optionalNonNegativeInt("level"),
            width = track.optionalPositiveInt("width"),
            height = track.optionalPositiveInt("height"),
            frameRate =
                if (frameRateNumerator != null && frameRateDenominator != null) {
                    frameRateNumerator.toDouble() / frameRateDenominator.toDouble()
                } else {
                    null
                },
            colorInfo =
                VideoColorInfo(
                    dynamicRange = dynamicRange,
                    bitDepth = track.optionalPositiveInt("bitDepth"),
                    range = parseRange(track.optionalString("colorRange")),
                    primaries = parsePrimaries(track.optionalString("colorPrimaries")),
                    transfer = parseTransfer(track.optionalString("colorTransfer")),
                    matrix = parseMatrix(track.optionalString("colorMatrix")),
                    masteringDisplay = parseMasteringDisplay(track),
                    contentLightLevel = parseContentLightLevel(track),
                    hasHdr10PlusMetadata = track.boolean("hasHdr10PlusMetadata"),
                    dolbyVision = dolbyVision,
                ),
        )
    }

    private fun parseMasteringDisplay(track: JsonObject): MasteringDisplayInfo? {
        val value = track["masteringDisplay"]?.takeUnless { it is JsonNull }?.jsonObject ?: return null
        return MasteringDisplayInfo(
            red = Chromaticity(value.double("redX"), value.double("redY")),
            green = Chromaticity(value.double("greenX"), value.double("greenY")),
            blue = Chromaticity(value.double("blueX"), value.double("blueY")),
            whitePoint = Chromaticity(value.double("whiteX"), value.double("whiteY")),
            minimumLuminanceNits = value.double("minimumLuminanceNits"),
            maximumLuminanceNits = value.double("maximumLuminanceNits"),
        )
    }

    private fun parseContentLightLevel(track: JsonObject): ContentLightLevelInfo? {
        val value = track["contentLightLevel"]?.takeUnless { it is JsonNull }?.jsonObject ?: return null
        return ContentLightLevelInfo(
            maximumContentLightLevelNits = value.int("maximumContentLightLevelNits"),
            maximumFrameAverageLightLevelNits = value.int("maximumFrameAverageLightLevelNits"),
        )
    }

    private fun parseContainer(name: String): MediaContainer {
        val normalized = name.lowercase()
        return when {
            "matroska" in normalized -> MediaContainer.MATROSKA
            normalized == "webm" -> MediaContainer.WEBM
            "mov" in normalized || "mp4" in normalized -> MediaContainer.MP4
            "mpegts" in normalized -> MediaContainer.MPEG_TS
            else -> MediaContainer.UNKNOWN
        }
    }

    private fun parseVideoCodec(
        name: String,
        dynamicRange: DynamicRangeFormat,
    ): VideoCodec =
        if (dynamicRange == DynamicRangeFormat.DOLBY_VISION) {
            VideoCodec.DOLBY_VISION
        } else {
            when (name.lowercase()) {
                "h264", "avc" -> VideoCodec.AVC
                "hevc", "h265" -> VideoCodec.HEVC
                "av1" -> VideoCodec.AV1
                "vp9" -> VideoCodec.VP9
                else -> VideoCodec.UNKNOWN
            }
        }

    private fun parseRange(value: String?): ColorRange =
        when (value?.lowercase()) {
            "tv", "mpeg", "limited" -> ColorRange.LIMITED
            "pc", "jpeg", "full" -> ColorRange.FULL
            else -> ColorRange.UNKNOWN
        }

    private fun parsePrimaries(value: String?): ColorPrimaries =
        when (value?.lowercase()) {
            "bt709" -> ColorPrimaries.BT709
            "bt2020" -> ColorPrimaries.BT2020
            "smpte432", "display-p3" -> ColorPrimaries.DISPLAY_P3
            else -> ColorPrimaries.UNKNOWN
        }

    private fun parseTransfer(value: String?): ColorTransfer =
        when (value?.lowercase()) {
            "bt709" -> ColorTransfer.BT709
            "iec61966-2-1", "srgb" -> ColorTransfer.SRGB
            "smpte2084", "pq" -> ColorTransfer.PQ
            "arib-std-b67", "hlg" -> ColorTransfer.HLG
            "linear" -> ColorTransfer.LINEAR
            else -> ColorTransfer.UNKNOWN
        }

    private fun parseMatrix(value: String?): ColorMatrix =
        when (value?.lowercase()) {
            "bt709" -> ColorMatrix.BT709
            "bt2020nc", "bt2020_ncl" -> ColorMatrix.BT2020_NCL
            "bt2020c", "bt2020_cl" -> ColorMatrix.BT2020_CL
            "gbr", "rgb", "identity" -> ColorMatrix.IDENTITY
            else -> ColorMatrix.UNKNOWN
        }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int =
        getValue(name).jsonPrimitive.intOrNull
            ?: throw IllegalArgumentException("The typed probe field $name is not an integer.")

    private fun JsonObject.double(name: String): Double =
        getValue(name)
            .jsonPrimitive.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?: throw IllegalArgumentException("The typed probe field $name is not a finite number.")

    private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.optionalPositiveInt(name: String): Int? =
        get(name)
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }

    private fun JsonObject.optionalNonNegativeInt(name: String): Int? =
        get(name)
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it >= 0 }

    private fun JsonObject.nullableLong(name: String): Long? = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
}
