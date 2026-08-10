/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One heart-rate value produced by the AirPods firmware. */
data class AirPodsHeartRateSample(
    val bpm: Int,
    val sequence: Long,
    val receivedAtMillis: Long,
    val statusTail: Int
)

data class HeartRateRouteResult(
    val samples: List<AirPodsHeartRateSample> = emptyList(),
    val passthroughPackets: List<ByteArray> = emptyList(),
    val suppressRawLogging: Boolean = false,
    val heartRateFrameCount: Int = 0,
    val diagnostics: List<String> = emptyList()
)

/**
 * Minimal encoder and streaming router for AirPods RTBuddy sensor messages.
 *
 * The encoder builds protobuf wire fields instead of storing captured complete packets. The router
 * retains partial sensor frames across Bluetooth socket reads and returns non-heart-rate frames to
 * the existing AACP parser, allowing head tracking and other opcode 0x17 traffic to keep working.
 */
class AirPodsHeartRateProtocol {
    private var pending = ByteArray(0)
    private var nextSequence = INITIAL_SEQUENCE

    @Synchronized
    fun reset() {
        pending = ByteArray(0)
        nextSequence = INITIAL_SEQUENCE
    }

    /** Builds one SensorDataWX feature-report write. */
    @Synchronized
    fun createServiceSettingPacket(service: Int, reportId: Int, value: Int): ByteArray {
        require(service in 0..0xFFFF)
        require(reportId in 0..0xFF)
        require(value >= 0)
        val setting = byteArrayOf(reportId.toByte()) + littleEndianInt(value)
        val command = protoVarint(1, service.toLong()) +
            protoVarint(2, COMMAND_SET.toLong()) +
            protoBytes(3, setting)
        val sensorData = protoVarint(1, nextSequence()) + protoBytes(COMMAND_FIELD, command)
        val sensorHeader = ByteBuffer.allocate(SENSOR_HEADER_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(SENSOR_OPCODE.toShort())
            .putInt(SENSOR_DESCRIPTOR)
            .putShort(sensorData.size.toShort())
            .array()
        return AACP_HEADER + sensorHeader + sensorData
    }

    /**
     * Builds the HRM feature report. A zero interval stops heart-rate sampling; a positive value
     * is expressed in microseconds.
     */
    fun createSamplingPacket(intervalMicros: Int): ByteArray =
        createServiceSettingPacket(HEART_RATE_SERVICE, REPORT_INTERVAL, intervalMicros)

    /**
     * Apple starts HRM together with DEVMOTION6. The order and values below were independently
     * recovered from the iOS 27 DataRelay HID writes made during a successful workout session.
     */
    fun createStartPackets(
        heartRateIntervalMicros: Int = 1_000_000,
        motionIntervalMicros: Int = 20_000
    ): List<ByteArray> = listOf(
        createServiceSettingPacket(
            HEART_RATE_SERVICE,
            REPORT_INTERVAL,
            heartRateIntervalMicros
        ),
        createServiceSettingPacket(DEVMOTION6_SERVICE, REPORT_MAX_FIFO_EVENTS, 10),
        createServiceSettingPacket(DEVMOTION6_SERVICE, REPORT_INTERVAL, motionIntervalMicros),
        createServiceSettingPacket(DEVMOTION6_SERVICE, REPORT_BATCH_INTERVAL, 1)
    )

    /** Routes arbitrary socket chunks, extracting only validated service-20 live samples. */
    @Synchronized
    fun route(chunk: ByteArray): HeartRateRouteResult {
        if (chunk.isEmpty()) return HeartRateRouteResult()

        val input = if (pending.isEmpty()) chunk else pending + chunk
        pending = ByteArray(0)
        val samples = mutableListOf<AirPodsHeartRateSample>()
        val passthrough = mutableListOf<ByteArray>()
        var suppressRawLogging = false
        var heartRateFrameCount = 0
        val diagnostics = mutableListOf<String>()
        var cursor = 0

        while (cursor < input.size) {
            val frameStart = input.indexOfPrefix(SENSOR_PREFIX, cursor)
            if (frameStart < 0) {
                val retained = input.longestSuffixMatchingPrefix(SENSOR_PREFIX, cursor)
                val passthroughEnd = input.size - retained
                if (passthroughEnd > cursor) {
                    passthrough += input.copyOfRange(cursor, passthroughEnd)
                }
                if (retained > 0) {
                    pending = input.copyOfRange(passthroughEnd, input.size)
                    suppressRawLogging = true
                }
                break
            }

            if (frameStart > cursor) {
                passthrough += input.copyOfRange(cursor, frameStart)
            }

            if (input.size - frameStart < FULL_SENSOR_HEADER_LENGTH) {
                pending = input.copyOfRange(frameStart, input.size)
                suppressRawLogging = true
                break
            }

            val payloadLength = input.readUnsignedLe16(frameStart + SENSOR_LENGTH_OFFSET)
            if (payloadLength > MAX_SENSOR_PAYLOAD) {
                // An exact sensor prefix with an unreasonable length is not forwarded as a normal
                // AACP packet. Dropping it also prevents health payloads from entering raw logs.
                suppressRawLogging = true
                cursor = frameStart + SENSOR_PREFIX.size
                continue
            }

            val frameLength = FULL_SENSOR_HEADER_LENGTH + payloadLength
            if (input.size - frameStart < frameLength) {
                pending = input.copyOfRange(frameStart, input.size)
                suppressRawLogging = true
                break
            }

            val frame = input.copyOfRange(frameStart, frameStart + frameLength)
            val decoded = decodeHeartRateFrame(frame)
            if (decoded.relatedToHeartRate) {
                suppressRawLogging = true
                heartRateFrameCount++
                decoded.diagnostic?.let(diagnostics::add)
                decoded.sample?.let(samples::add)
            } else {
                passthrough += frame
            }
            cursor = frameStart + frameLength
        }

        return HeartRateRouteResult(
            samples = samples,
            passthroughPackets = passthrough,
            suppressRawLogging = suppressRawLogging,
            heartRateFrameCount = heartRateFrameCount,
            diagnostics = diagnostics
        )
    }

    private fun decodeHeartRateFrame(frame: ByteArray): DecodedFrame {
        val payloadStart = FULL_SENSOR_HEADER_LENGTH
        val top = parseMessage(frame, payloadStart, frame.size) ?: return DecodedFrame()
        val sequence = top.firstVarint(1) ?: -1L
        val logType = top.firstVarint(2)?.toInt() ?: -1
        val directServices = top.entries
            .filter { it.wireType == WIRE_LENGTH && it.field in COMMAND_FIELDS }
            .associate { entry ->
                entry.field to parseMessage(frame, entry.valueStart, entry.valueEnd)
                    ?.firstVarint(1)?.toInt()
            }
        val commands = mutableListOf<ProtoMessage>()

        top.entries
            .filter { it.wireType == WIRE_LENGTH && it.field in COMMAND_FIELDS }
            .forEach { entry ->
                collectCommandMessages(frame, entry.valueStart, entry.valueEnd, 0, commands)
            }

        val heartRateCommands = commands.filter { it.firstVarint(1) == HEART_RATE_SERVICE.toLong() }
        if (heartRateCommands.isEmpty()) return DecodedFrame()
        val commandPayloadLength = top.entries.firstOrNull {
            it.field == 7 && it.wireType == WIRE_LENGTH
        }?.let { commandEntry ->
            parseMessage(frame, commandEntry.valueStart, commandEntry.valueEnd)?.entries
                ?.firstOrNull { it.field == 3 && it.wireType == WIRE_LENGTH }
                ?.let { it.valueEnd - it.valueStart }
        }
        val diagnostic = "seq=$sequence state=$logType setting=${directServices[8]} " +
            "command=${directServices[7]} commandBytes=${commandPayloadLength ?: 0} " +
            "startAck=${directServices[9]} commandAck=${directServices[12]}"
        if (logType !in LIVE_LOG_TYPES) {
            return DecodedFrame(relatedToHeartRate = true, diagnostic = diagnostic)
        }

        val candidates = mutableListOf<ByteArray>()
        heartRateCommands.forEach { command ->
            command.entries
                .filter { it.field == 3 && it.wireType == WIRE_LENGTH }
                .forEach { entry ->
                    collectPayloadCandidates(frame, entry.valueStart, entry.valueEnd, 0, candidates)
                }
        }

        val payload = candidates.firstOrNull(::isValidatedHeartRatePayload)
            ?: return DecodedFrame(relatedToHeartRate = true, diagnostic = diagnostic)
        val statusOffset = payload.size - STATUS_TAIL_LENGTH
        val statusTail = payload.readUnsignedLe24(statusOffset)
        return DecodedFrame(
            relatedToHeartRate = true,
            diagnostic = diagnostic,
            sample = AirPodsHeartRateSample(
                bpm = payload[BPM_OFFSET].toInt() and 0xFF,
                sequence = sequence,
                receivedAtMillis = System.currentTimeMillis(),
                statusTail = statusTail
            )
        )
    }

    private fun collectCommandMessages(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        output: MutableList<ProtoMessage>
    ) {
        if (depth > MAX_NESTING || output.size >= MAX_COMMANDS) return
        val message = parseMessage(data, start, end) ?: return
        if (message.firstVarint(1) != null) output += message
        if (depth == MAX_NESTING) return
        message.entries
            .filter { it.wireType == WIRE_LENGTH }
            .forEach { entry ->
                collectCommandMessages(data, entry.valueStart, entry.valueEnd, depth + 1, output)
            }
    }

    private fun collectPayloadCandidates(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        output: MutableList<ByteArray>
    ) {
        if (output.size >= MAX_PAYLOAD_CANDIDATES) return
        val direct = data.copyOfRange(start, end)
        if (output.none { it.contentEquals(direct) }) output += direct
        if (depth == MAX_NESTING) return

        val wrapper = parseMessage(data, start, end) ?: return
        wrapper.entries
            .filter { it.wireType == WIRE_LENGTH }
            .forEach { entry ->
                collectPayloadCandidates(data, entry.valueStart, entry.valueEnd, depth + 1, output)
            }
    }

    private fun isValidatedHeartRatePayload(payload: ByteArray): Boolean {
        if (payload.size != HEART_RATE_PAYLOAD_LENGTH) return false
        val bpm = payload[BPM_OFFSET].toInt() and 0xFF
        if (bpm !in MIN_BPM..MAX_BPM) return false
        val status = payload.readUnsignedLe24(payload.size - STATUS_TAIL_LENGTH)
        return status in LIVE_STATUS_TAILS
    }

    private fun parseMessage(data: ByteArray, start: Int, end: Int): ProtoMessage? {
        if (start < 0 || end < start || end > data.size || end - start > MAX_SENSOR_PAYLOAD) {
            return null
        }
        var cursor = start
        val entries = mutableListOf<ProtoEntry>()
        while (cursor < end) {
            if (entries.size >= MAX_PROTO_FIELDS) return null
            val key = readVarint(data, cursor, end) ?: return null
            cursor = key.next
            val field = (key.value ushr 3).toInt()
            val wireType = (key.value and 7).toInt()
            if (field <= 0) return null

            when (wireType) {
                WIRE_VARINT -> {
                    val value = readVarint(data, cursor, end) ?: return null
                    entries += ProtoEntry(field, wireType, value.value, cursor, value.next)
                    cursor = value.next
                }

                WIRE_FIXED64 -> {
                    if (end - cursor < 8) return null
                    entries += ProtoEntry(field, wireType, null, cursor, cursor + 8)
                    cursor += 8
                }

                WIRE_LENGTH -> {
                    val length = readVarint(data, cursor, end) ?: return null
                    if (length.value > Int.MAX_VALUE) return null
                    val valueStart = length.next
                    val valueEnd = valueStart + length.value.toInt()
                    if (valueEnd < valueStart || valueEnd > end) return null
                    entries += ProtoEntry(field, wireType, null, valueStart, valueEnd)
                    cursor = valueEnd
                }

                WIRE_FIXED32 -> {
                    if (end - cursor < 4) return null
                    entries += ProtoEntry(field, wireType, null, cursor, cursor + 4)
                    cursor += 4
                }

                else -> return null
            }
        }
        return ProtoMessage(entries)
    }

    private fun readVarint(data: ByteArray, start: Int, end: Int): VarintRead? {
        var value = 0L
        var shift = 0
        var cursor = start
        while (cursor < end && shift < 64) {
            val byte = data[cursor++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return VarintRead(value, cursor)
            shift += 7
        }
        return null
    }

    private fun protoVarint(field: Int, value: Long): ByteArray =
        encodeVarint((field.toLong() shl 3) or WIRE_VARINT.toLong()) + encodeVarint(value)

    private fun protoBytes(field: Int, value: ByteArray): ByteArray =
        encodeVarint((field.toLong() shl 3) or WIRE_LENGTH.toLong()) +
            encodeVarint(value.size.toLong()) + value

    private fun encodeVarint(input: Long): ByteArray {
        require(input >= 0)
        var value = input
        val output = ArrayList<Byte>(10)
        do {
            var next = (value and 0x7F).toInt()
            value = value ushr 7
            if (value != 0L) next = next or 0x80
            output += next.toByte()
        } while (value != 0L)
        return output.toByteArray()
    }

    private fun nextSequence(): Long {
        val result = nextSequence
        nextSequence++
        if (nextSequence > MAX_SEQUENCE) nextSequence = INITIAL_SEQUENCE
        return result
    }

    private data class DecodedFrame(
        val relatedToHeartRate: Boolean = false,
        val diagnostic: String? = null,
        val sample: AirPodsHeartRateSample? = null
    )

    private data class ProtoMessage(val entries: List<ProtoEntry>) {
        fun firstVarint(field: Int): Long? = entries.firstOrNull {
            it.field == field && it.wireType == WIRE_VARINT
        }?.varint
    }

    private data class ProtoEntry(
        val field: Int,
        val wireType: Int,
        val varint: Long?,
        val valueStart: Int,
        val valueEnd: Int
    )

    private data class VarintRead(val value: Long, val next: Int)

    companion object {
        private val AACP_HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)
        private const val SENSOR_OPCODE = 0x17
        private const val SENSOR_DESCRIPTOR = 0x00100000
        private const val SENSOR_HEADER_LENGTH = 8
        private const val FULL_SENSOR_HEADER_LENGTH = 12
        private const val SENSOR_LENGTH_OFFSET = 10
        private val SENSOR_PREFIX = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x17, 0x00,
            0x00, 0x00, 0x10, 0x00
        )

        private const val DEVMOTION6_SERVICE = 16
        private const val HEART_RATE_SERVICE = 20
        private const val COMMAND_SET = 2
        private const val COMMAND_FIELD = 8
        private const val REPORT_INTERVAL = 1
        private const val REPORT_BATCH_INTERVAL = 2
        private const val REPORT_MAX_FIFO_EVENTS = 4
        // Apple's four successful feature writes are all 15-byte protobuf messages, which keeps
        // the message sequence in the single-byte varint range.
        private const val INITIAL_SEQUENCE = 0L
        private const val MAX_SEQUENCE = 0x7FL

        private val COMMAND_FIELDS = setOf(5, 7, 8, 9, 12)
        private val LIVE_LOG_TYPES = setOf(1, 3)
        private val LIVE_STATUS_TAILS = setOf(
            0x000010,
            0x020010,
            0x000020,
            0x800220,
            0x808220
        )
        private const val HEART_RATE_PAYLOAD_LENGTH = 18
        private const val BPM_OFFSET = 1
        private const val STATUS_TAIL_LENGTH = 3
        private const val MIN_BPM = 30
        private const val MAX_BPM = 220

        private const val MAX_SENSOR_PAYLOAD = 16 * 1024
        private const val MAX_NESTING = 3
        private const val MAX_COMMANDS = 24
        private const val MAX_PAYLOAD_CANDIDATES = 16
        private const val MAX_PROTO_FIELDS = 128

        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH = 2
        private const val WIRE_FIXED32 = 5
    }
}

private fun littleEndianInt(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES)
    .order(ByteOrder.LITTLE_ENDIAN)
    .putInt(value)
    .array()

private fun ByteArray.readUnsignedLe16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.readUnsignedLe24(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

private fun ByteArray.indexOfPrefix(prefix: ByteArray, start: Int): Int {
    if (prefix.isEmpty()) return start.coerceIn(0, size)
    val finalStart = size - prefix.size
    if (start > finalStart) return -1
    for (candidate in start.coerceAtLeast(0)..finalStart) {
        if (prefix.indices.all { this[candidate + it] == prefix[it] }) return candidate
    }
    return -1
}

private fun ByteArray.longestSuffixMatchingPrefix(prefix: ByteArray, start: Int): Int {
    val available = size - start.coerceIn(0, size)
    for (length in minOf(available, prefix.size - 1) downTo 1) {
        val suffixStart = size - length
        if ((0 until length).all { this[suffixStart + it] == prefix[it] }) return length
    }
    return 0
}
