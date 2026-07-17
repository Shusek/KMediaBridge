// SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

package io.github.shusek.kmediabridge.ffmpeg

import io.github.shusek.kmediabridge.MediaBridgeErrorCode
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediabridge.MediaFragment
import java.io.ByteArrayOutputStream
import kotlin.math.min

internal class FragmentedMp4Framer(
    private val requestedStartTimeUs: Long,
    private val requestedDurationUs: Long,
) {
    private val header = ByteArray(16)
    private var headerLength = 0
    private var requiredHeaderLength = 8
    private var remainingBodyBytes = NO_OPEN_BOX
    private var initialization = ByteArrayOutputStream()
    private var fragment = ByteArrayOutputStream()
    private var initializationEmitted = false
    private var mediaStarted = false
    private var sequence = 1L
    private var timingReader: Mp4TimingReader? = null

    fun accept(bytes: ByteArray): List<MediaBridgeEvent.Fragment> {
        val events = mutableListOf<MediaBridgeEvent.Fragment>()
        var offset = 0
        while (offset < bytes.size) {
            if (remainingBodyBytes == NO_OPEN_BOX) {
                val copied = min(requiredHeaderLength - headerLength, bytes.size - offset)
                bytes.copyInto(header, headerLength, offset, offset + copied)
                headerLength += copied
                offset += copied
                if (headerLength < requiredHeaderLength) continue

                if (requiredHeaderLength == 8 && readUnsignedInt(header, 0) == 1L) {
                    requiredHeaderLength = 16
                    continue
                }
                val declaredSize =
                    when (val shortSize = readUnsignedInt(header, 0)) {
                        0L -> Long.MAX_VALUE
                        1L -> readSignedLong(header, 8).takeIf { it >= 16L } ?: invalidStream()
                        else -> shortSize.takeIf { it >= 8L } ?: invalidStream()
                    }
                if (declaredSize != Long.MAX_VALUE && declaredSize > MAX_GROUP_BYTES) {
                    throw MediaBridgeException(
                        MediaBridgeErrorCode.CONVERSION_FAILED,
                        "A generated MP4 box exceeds the bounded fragment memory limit.",
                    )
                }
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (type == "moof") {
                    if (mediaStarted && fragment.size() > 0) events += emitFragment()
                    if (!initializationEmitted) events += emitInitialization()
                    mediaStarted = true
                }
                target().write(header, 0, requiredHeaderLength)
                remainingBodyBytes =
                    if (declaredSize == Long.MAX_VALUE) Long.MAX_VALUE else declaredSize - requiredHeaderLength
                headerLength = 0
                requiredHeaderLength = 8
                if (remainingBodyBytes == 0L) remainingBodyBytes = NO_OPEN_BOX
            } else {
                val copied =
                    if (remainingBodyBytes == Long.MAX_VALUE) {
                        bytes.size - offset
                    } else {
                        min(remainingBodyBytes, (bytes.size - offset).toLong()).toInt()
                    }
                ensureCapacity(copied)
                target().write(bytes, offset, copied)
                offset += copied
                if (remainingBodyBytes != Long.MAX_VALUE) {
                    remainingBodyBytes -= copied.toLong()
                    if (remainingBodyBytes == 0L) remainingBodyBytes = NO_OPEN_BOX
                }
            }
        }
        return events
    }

    fun finish(): List<MediaBridgeEvent.Fragment> {
        if (headerLength != 0 || (remainingBodyBytes != NO_OPEN_BOX && remainingBodyBytes != Long.MAX_VALUE)) {
            invalidStream()
        }
        val events = mutableListOf<MediaBridgeEvent.Fragment>()
        if (!initializationEmitted && initialization.size() > 0) events += emitInitialization()
        if (fragment.size() > 0) events += emitFragment()
        return events
    }

    private fun target(): ByteArrayOutputStream = if (mediaStarted) fragment else initialization

    private fun ensureCapacity(additional: Int) {
        if (target().size().toLong() + additional.toLong() > MAX_GROUP_BYTES) {
            throw MediaBridgeException(
                MediaBridgeErrorCode.CONVERSION_FAILED,
                "A generated CMAF fragment exceeds the bounded fragment memory limit.",
            )
        }
    }

    private fun emitInitialization(): MediaBridgeEvent.Fragment {
        val bytes = initialization.toByteArray()
        initialization = ByteArrayOutputStream()
        initializationEmitted = true
        timingReader = Mp4TimingReader(bytes)
        return MediaBridgeEvent.Fragment(
            MediaFragment(
                sequence = 0L,
                presentationTimeUs = 0L,
                durationUs = 0L,
                isInitialization = true,
                bytes = bytes,
            ),
        )
    }

    private fun emitFragment(): MediaBridgeEvent.Fragment {
        val bytes = fragment.toByteArray()
        fragment = ByteArrayOutputStream()
        val currentSequence = sequence++
        val timing = timingReader?.readFragmentTiming(bytes)
        val fallbackStart = requestedStartTimeUs + (currentSequence - 1L) * requestedDurationUs
        return MediaBridgeEvent.Fragment(
            MediaFragment(
                sequence = currentSequence,
                presentationTimeUs = timing?.presentationTimeUs ?: fallbackStart,
                durationUs = timing?.durationUs?.takeIf { it > 0L } ?: requestedDurationUs,
                isInitialization = false,
                bytes = bytes,
            ),
        )
    }

    private fun invalidStream(): Nothing =
        throw MediaBridgeException(
            MediaBridgeErrorCode.CONVERSION_FAILED,
            "The native runtime emitted a malformed fragmented MP4 stream.",
        )

    private companion object {
        const val NO_OPEN_BOX: Long = -1L
        const val MAX_GROUP_BYTES: Long = 512L * 1024L * 1024L
    }
}

private data class FragmentTiming(
    val presentationTimeUs: Long,
    val durationUs: Long,
)

private class Mp4TimingReader(
    initialization: ByteArray,
) {
    private val timescales: Map<Long, Long> = readTrackTimescales(initialization)

    fun readFragmentTiming(fragment: ByteArray): FragmentTiming? {
        val ranges = mutableListOf<LongRange>()
        boxes(fragment, 0, fragment.size)
            .filter { it.type == "moof" }
            .flatMap { moof -> boxes(fragment, moof.contentStart, moof.end) }
            .filter { it.type == "traf" }
            .mapNotNull { traf -> readTrackRange(fragment, traf) }
            .forEach(ranges::add)
        if (ranges.isEmpty()) return null
        val start = ranges.minOf(LongRange::first)
        val end = ranges.maxOf(LongRange::last)
        return FragmentTiming(start, (end - start).coerceAtLeast(0L))
    }

    private fun readTrackRange(
        data: ByteArray,
        traf: Box,
    ): LongRange? {
        val children = boxes(data, traf.contentStart, traf.end).toList()
        val tfhd = children.firstOrNull { it.type == "tfhd" } ?: return null
        val trackId = unsignedInt(data, tfhd.contentStart + 4) ?: return null
        val timescale = timescales[trackId]?.takeIf { it > 0L } ?: return null
        val defaultDuration = readDefaultDuration(data, tfhd)
        val tfdt = children.firstOrNull { it.type == "tfdt" } ?: return null
        val decodeTime = readBaseDecodeTime(data, tfdt) ?: return null
        val duration =
            children
                .filter { it.type == "trun" }
                .mapNotNull { readRunDuration(data, it, defaultDuration) }
                .sum()
                .takeIf { it > 0L }
                ?: return null
        val startUs = scaleToMicroseconds(decodeTime, timescale) ?: return null
        val durationUs = scaleToMicroseconds(duration, timescale) ?: return null
        return startUs..(startUs + durationUs)
    }

    private fun readTrackTimescales(data: ByteArray): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        val moov = boxes(data, 0, data.size).firstOrNull { it.type == "moov" } ?: return emptyMap()
        boxes(data, moov.contentStart, moov.end)
            .filter { it.type == "trak" }
            .forEach { trak ->
                val trackChildren = boxes(data, trak.contentStart, trak.end).toList()
                val tkhd = trackChildren.firstOrNull { it.type == "tkhd" } ?: return@forEach
                val trackId = readTrackId(data, tkhd) ?: return@forEach
                val mdia = trackChildren.firstOrNull { it.type == "mdia" } ?: return@forEach
                val mdhd = boxes(data, mdia.contentStart, mdia.end).firstOrNull { it.type == "mdhd" } ?: return@forEach
                val timescale = readTimescale(data, mdhd) ?: return@forEach
                result[trackId] = timescale
            }
        return result
    }

    private fun readTrackId(
        data: ByteArray,
        box: Box,
    ): Long? {
        val version = unsignedByte(data, box.contentStart) ?: return null
        val offset = box.contentStart + if (version == 1) 20 else 12
        return unsignedInt(data, offset)
    }

    private fun readTimescale(
        data: ByteArray,
        box: Box,
    ): Long? {
        val version = unsignedByte(data, box.contentStart) ?: return null
        val offset = box.contentStart + if (version == 1) 20 else 12
        return unsignedInt(data, offset)
    }

    private fun readDefaultDuration(
        data: ByteArray,
        box: Box,
    ): Long? {
        val flags = fullBoxFlags(data, box.contentStart) ?: return null
        var cursor = box.contentStart + 8
        if (flags and 0x000001 != 0) cursor += 8
        if (flags and 0x000002 != 0) cursor += 4
        return if (flags and 0x000008 != 0) unsignedInt(data, cursor) else null
    }

    private fun readBaseDecodeTime(
        data: ByteArray,
        box: Box,
    ): Long? {
        val version = unsignedByte(data, box.contentStart) ?: return null
        return if (version == 1) unsignedLong(data, box.contentStart + 4) else unsignedInt(data, box.contentStart + 4)
    }

    private fun readRunDuration(
        data: ByteArray,
        box: Box,
        defaultDuration: Long?,
    ): Long? {
        val flags = fullBoxFlags(data, box.contentStart) ?: return null
        val sampleCount = unsignedInt(data, box.contentStart + 4)?.toInt() ?: return null
        if (sampleCount < 0) return null
        var cursor = box.contentStart + 8
        if (flags and 0x000001 != 0) cursor += 4
        if (flags and 0x000004 != 0) cursor += 4
        var duration = 0L
        repeat(sampleCount) {
            val sampleDuration =
                if (flags and 0x000100 != 0) {
                    unsignedInt(data, cursor).also { cursor += 4 }
                } else {
                    defaultDuration
                } ?: return null
            duration += sampleDuration
            if (flags and 0x000200 != 0) cursor += 4
            if (flags and 0x000400 != 0) cursor += 4
            if (flags and 0x000800 != 0) cursor += 4
            if (cursor > box.end) return null
        }
        return duration
    }
}

private data class Box(
    val type: String,
    val contentStart: Int,
    val end: Int,
)

private fun boxes(
    data: ByteArray,
    start: Int,
    end: Int,
): Sequence<Box> =
    sequence {
        var cursor = start
        while (cursor + 8 <= end) {
            val shortSize = unsignedInt(data, cursor) ?: break
            val type = String(data, cursor + 4, 4, Charsets.US_ASCII)
            val headerSize = if (shortSize == 1L) 16 else 8
            val size =
                when (shortSize) {
                    0L -> (end - cursor).toLong()
                    1L -> unsignedLong(data, cursor + 8) ?: break
                    else -> shortSize
                }
            if (size < headerSize || size > Int.MAX_VALUE || cursor + size.toInt() > end) break
            val boxEnd = cursor + size.toInt()
            yield(Box(type, cursor + headerSize, boxEnd))
            cursor = boxEnd
        }
    }

private fun fullBoxFlags(
    data: ByteArray,
    offset: Int,
): Int? {
    if (offset < 0 || offset + 4 > data.size) return null
    return ((data[offset + 1].toInt() and 0xff) shl 16) or
        ((data[offset + 2].toInt() and 0xff) shl 8) or
        (data[offset + 3].toInt() and 0xff)
}

private fun unsignedByte(
    data: ByteArray,
    offset: Int,
): Int? = data.getOrNull(offset)?.toInt()?.and(0xff)

private fun unsignedInt(
    data: ByteArray,
    offset: Int,
): Long? {
    if (offset < 0 || offset + 4 > data.size) return null
    return readUnsignedInt(data, offset)
}

private fun unsignedLong(
    data: ByteArray,
    offset: Int,
): Long? {
    if (offset < 0 || offset + 8 > data.size) return null
    val value = readSignedLong(data, offset)
    return value.takeIf { it >= 0L }
}

private fun readUnsignedInt(
    data: ByteArray,
    offset: Int,
): Long =
    ((data[offset].toLong() and 0xffL) shl 24) or
        ((data[offset + 1].toLong() and 0xffL) shl 16) or
        ((data[offset + 2].toLong() and 0xffL) shl 8) or
        (data[offset + 3].toLong() and 0xffL)

private fun readSignedLong(
    data: ByteArray,
    offset: Int,
): Long {
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (data[offset + index].toLong() and 0xffL) }
    return value
}

private fun scaleToMicroseconds(
    value: Long,
    timescale: Long,
): Long? {
    if (value < 0L || timescale <= 0L) return null
    val seconds = value / timescale
    if (seconds > Long.MAX_VALUE / 1_000_000L) return null
    return seconds * 1_000_000L + (value % timescale) * 1_000_000L / timescale
}
