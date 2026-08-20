package me.kavishdevar.librepods.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsHeartRateProtocolTest {
    @Test
    fun samplingPacketIsBuiltFromServiceSettingFields() {
        val packet = AirPodsHeartRateProtocol().createSamplingPacket(1_000_000)

        assertArrayEquals(
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00, 0x10, 0x00),
            packet.copyOfRange(0, 10)
        )
        assertEquals(packet.size - 12, packet.readLe16(10))
        assertTrue(packet.containsSubsequence(byteArrayOf(0x08, 0x14, 0x10, 0x02, 0x1A, 0x05)))
        assertTrue(packet.containsSubsequence(byteArrayOf(0x01, 0x40, 0x42, 0x0F, 0x00)))
    }

    @Test
    fun resetRestoresHealthClientSequenceSeed() {
        val protocol = AirPodsHeartRateProtocol()
        protocol.createSamplingPacket(1_000_000)
        protocol.reset()

        val restarted = protocol.createSamplingPacket(1_000_000)

        assertEquals(0x08.toByte(), restarted[12])
        assertEquals(0x00.toByte(), restarted[13])
        assertEquals(27, restarted.size)
    }

    @Test
    fun ios27StartSequenceMatchesFourHidFeatureReports() {
        val protocol = AirPodsHeartRateProtocol()
        val resolution = protocol.prepareSamplingSession()
        val packets = protocol.createStartPackets()

        assertEquals(20, resolution.serviceId)
        assertEquals(HeartRateServiceResolution.Source.IOS27_FALLBACK, resolution.source)
        assertEquals(4, packets.size)
        packets.forEach { assertEquals(27, it.size) }
        assertArrayEquals(
            byteArrayOf(0x08, 0x00, 0x42, 0x0B, 0x08, 0x14, 0x10, 0x02,
                0x1A, 0x05, 0x01, 0x40, 0x42, 0x0F, 0x00),
            packets[0].copyOfRange(12, 27)
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x01, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02,
                0x1A, 0x05, 0x04, 0x0A, 0x00, 0x00, 0x00),
            packets[1].copyOfRange(12, 27)
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x02, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02,
                0x1A, 0x05, 0x01, 0x20, 0x4E, 0x00, 0x00),
            packets[2].copyOfRange(12, 27)
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x03, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02,
                0x1A, 0x05, 0x02, 0x01, 0x00, 0x00, 0x00),
            packets[3].copyOfRange(12, 27)
        )
    }

    @Test
    fun validHeartRateFrameProducesSample() {
        val result = AirPodsHeartRateProtocol().route(heartRateFrame(bpm = 72, sequence = 91))

        assertTrue(result.suppressRawLogging)
        assertEquals(0, result.passthroughPackets.size)
        assertEquals(1, result.samples.size)
        assertEquals(72, result.samples.single().bpm)
        assertEquals(91L, result.samples.single().sequence)
        assertEquals(0x800220, result.samples.single().statusTail)
        assertEquals(20, result.samples.single().service)
    }

    @Test
    fun ios27TransitionStatusProducesSample() {
        val result = AirPodsHeartRateProtocol().route(
            heartRateFrame(bpm = 73, sequence = 92, statusTail = 0x020010)
        )

        assertEquals(73, result.samples.single().bpm)
        assertEquals(0x020010, result.samples.single().statusTail)
    }

    @Test
    fun legacyService19FrameProducesValidatedSampleWhenNoServiceWasResolved() {
        val result = AirPodsHeartRateProtocol().route(
            heartRateFrame(bpm = 72, sequence = 93, service = 19)
        )

        assertTrue(result.suppressRawLogging)
        assertEquals(72, result.samples.single().bpm)
        assertEquals(19, result.samples.single().service)
        assertEquals(0, result.passthroughPackets.size)
    }

    @Test
    fun ios26MetadataSelectsService19ForStartAndStop() {
        val protocol = AirPodsHeartRateProtocol()

        val metadata = protocol.route(serviceMetadataFrame(19, "HeartRateService"))
        val resolution = protocol.prepareSamplingSession()
        val startPackets = protocol.createStartPackets()
        val start = startPackets.first()
        val stop = protocol.createSamplingPacket(0)

        assertTrue(metadata.suppressRawLogging)
        assertEquals(19, metadata.serviceResolutionChanged?.serviceId)
        assertEquals(HeartRateServiceResolution.Source.METADATA, resolution.source)
        assertEquals(1, startPackets.size)
        assertTrue(start.containsSubsequence(byteArrayOf(0x08, 0x13, 0x10, 0x02)))
        assertTrue(stop.containsSubsequence(byteArrayOf(0x08, 0x13, 0x10, 0x02)))
    }

    @Test
    fun ios27MetadataExcludesHostLibHidService19AndSelectsService20() {
        val protocol = AirPodsHeartRateProtocol()
        val metadata = serviceMetadataFrame(19, "HostLibHID") +
            serviceMetadataFrame(20, "HeartRateService")

        val routed = protocol.route(metadata)
        val resolution = protocol.prepareSamplingSession()
        val start = protocol.createStartPackets().first()

        assertTrue(routed.suppressRawLogging)
        assertEquals(20, resolution.serviceId)
        assertEquals(HeartRateServiceResolution.Source.METADATA, resolution.source)
        assertTrue(start.containsSubsequence(byteArrayOf(0x08, 0x14, 0x10, 0x02)))
    }

    @Test
    fun serviceMetadataCanSpanMultipleSocketReads() {
        val protocol = AirPodsHeartRateProtocol()
        val frame = serviceMetadataFrame(19, "HeartRateService")

        val first = protocol.route(frame.copyOfRange(0, 9))
        val second = protocol.route(frame.copyOfRange(9, frame.size))

        assertTrue(first.suppressRawLogging)
        assertEquals(19, second.serviceResolutionChanged?.serviceId)
        assertEquals(19, protocol.prepareSamplingSession().serviceId)
    }

    @Test
    fun firstSampleTimeoutFallsBackFromIos27Service20ToIos26Service19() {
        val protocol = AirPodsHeartRateProtocol()
        val first = protocol.prepareSamplingSession()
        val firstStart = protocol.createStartPackets().first()
        val firstStop = protocol.createSamplingPacket(0)

        val fallback = protocol.advanceFallbackAfterFirstSampleTimeout()
        val second = protocol.prepareSamplingSession()
        val secondStart = protocol.createStartPackets().first()

        assertEquals(20, first.serviceId)
        assertTrue(firstStart.containsSubsequence(byteArrayOf(0x08, 0x14, 0x10, 0x02)))
        assertTrue(firstStop.containsSubsequence(byteArrayOf(0x08, 0x14, 0x10, 0x02)))
        assertEquals(19, fallback.serviceId)
        assertEquals(HeartRateServiceResolution.Source.IOS26_FALLBACK, fallback.source)
        assertEquals(19, second.serviceId)
        assertTrue(secondStart.containsSubsequence(byteArrayOf(0x08, 0x13, 0x10, 0x02)))
    }

    @Test
    fun metadataChangeDoesNotRedirectStopForActiveSession() {
        val protocol = AirPodsHeartRateProtocol()
        protocol.prepareSamplingSession()
        protocol.createStartPackets()

        protocol.route(serviceMetadataFrame(19, "HeartRateService"))
        val stop = protocol.createSamplingPacket(0)

        assertTrue(stop.containsSubsequence(byteArrayOf(0x08, 0x14, 0x10, 0x02)))
        assertFalse(stop.containsSubsequence(byteArrayOf(0x08, 0x13, 0x10, 0x02)))
    }

    @Test
    fun legacyDiscoveryCanRefreshPreparedFallbackBeforeStart() {
        val protocol = AirPodsHeartRateProtocol()
        protocol.prepareSamplingSession()
        protocol.advanceFallbackAfterFirstSampleTimeout()
        assertEquals(19, protocol.prepareSamplingSession().serviceId)

        protocol.route(serviceMetadataFrame(20, "HeartRateService"))
        val refreshed = protocol.refreshPreparedServiceResolution()
        val packets = protocol.createStartPackets()

        assertEquals(20, refreshed.serviceId)
        assertEquals(HeartRateServiceResolution.Source.METADATA, refreshed.source)
        assertEquals(4, packets.size)
        assertTrue(packets.first().containsSubsequence(byteArrayOf(0x08, 0x14, 0x10, 0x02)))
    }

    @Test
    fun legacyIos26StatusTailIsAccepted() {
        val result = AirPodsHeartRateProtocol().route(
            heartRateFrame(bpm = 68, sequence = 94, statusTail = 0x800010, service = 19)
        )

        assertEquals(68, result.samples.single().bpm)
        assertEquals(19, result.samples.single().service)
        assertEquals(0x800010, result.samples.single().statusTail)
    }

    @Test
    fun frameCanSpanMultipleSocketReads() {
        val protocol = AirPodsHeartRateProtocol()
        val frame = heartRateFrame(bpm = 63, sequence = 7)

        val first = protocol.route(frame.copyOfRange(0, 9))
        val second = protocol.route(frame.copyOfRange(9, frame.size))

        assertTrue(first.suppressRawLogging)
        assertTrue(first.samples.isEmpty())
        assertEquals(63, second.samples.single().bpm)
    }

    @Test
    fun nonHeartRateSensorFrameIsReturnedToExistingParser() {
        val result = AirPodsHeartRateProtocol().route(sensorFrame(service = 14, payload = byteArrayOf(1)))

        assertFalse(result.suppressRawLogging)
        assertTrue(result.samples.isEmpty())
        assertEquals(1, result.passthroughPackets.size)
    }

    @Test
    fun invalidBpmIsSuppressedButNeverPublished() {
        val result = AirPodsHeartRateProtocol().route(heartRateFrame(bpm = 250, sequence = 22))

        assertTrue(result.suppressRawLogging)
        assertTrue(result.samples.isEmpty())
    }

    @Test
    fun serviceSettingAcknowledgementIsExposedForOrderlyStop() {
        val command = fieldVarint(1, 20)
        val frame = wrapSensorPayload(
            fieldVarint(1, 94) + fieldVarint(2, 1) + fieldBytes(9, command)
        )

        val result = AirPodsHeartRateProtocol().route(frame)

        assertEquals(1, result.heartRateFrameCount)
        assertEquals(1, result.serviceSettingAcknowledgementCount)
        assertTrue(result.samples.isEmpty())
    }

    @Test
    fun commandAcknowledgementIsExposedForOrderlyStop() {
        val command = fieldVarint(1, 20)
        val frame = wrapSensorPayload(
            fieldVarint(1, 95) + fieldVarint(2, 1) + fieldBytes(12, command)
        )

        val result = AirPodsHeartRateProtocol().route(frame)

        assertEquals(1, result.heartRateFrameCount)
        assertEquals(1, result.serviceSettingAcknowledgementCount)
        assertTrue(result.samples.isEmpty())
    }

    private fun heartRateFrame(
        bpm: Int,
        sequence: Int,
        statusTail: Int = 0x800220,
        service: Int = 20
    ): ByteArray {
        val payload = ByteArray(18).apply {
            this[1] = bpm.toByte()
            this[15] = statusTail.toByte()
            this[16] = (statusTail ushr 8).toByte()
            this[17] = (statusTail ushr 16).toByte()
        }
        val command = fieldVarint(1, service) + fieldVarint(2, 2) + fieldBytes(3, payload)
        val top = fieldVarint(1, sequence) + fieldVarint(2, 3) + fieldBytes(8, command)
        return wrapSensorPayload(top)
    }

    private fun sensorFrame(service: Int, payload: ByteArray): ByteArray {
        val command = fieldVarint(1, service) + fieldVarint(2, 2) + fieldBytes(3, payload)
        return wrapSensorPayload(fieldVarint(1, 1) + fieldVarint(2, 3) + fieldBytes(8, command))
    }

    private fun serviceMetadataFrame(service: Int, marker: String): ByteArray {
        val record = fieldVarint(1, service) + fieldBytes(2, marker.encodeToByteArray())
        return wrapSensorPayload(fieldVarint(1, 1) + fieldBytes(5, record))
    }

    private fun wrapSensorPayload(payload: ByteArray): ByteArray =
        byteArrayOf(0x04, 0x00, 0x04, 0x00) +
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x17.toShort())
                .putInt(0x00100000)
                .putShort(payload.size.toShort())
                .array() +
            payload

    private fun fieldVarint(field: Int, value: Int): ByteArray =
        varint(field shl 3) + varint(value)

    private fun fieldBytes(field: Int, value: ByteArray): ByteArray =
        varint((field shl 3) or 2) + varint(value.size) + value

    private fun varint(input: Int): ByteArray {
        var value = input
        val result = mutableListOf<Byte>()
        do {
            var next = value and 0x7F
            value = value ushr 7
            if (value != 0) next = next or 0x80
            result += next.toByte()
        } while (value != 0)
        return result.toByteArray()
    }
}

private fun ByteArray.readLe16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean {
    if (expected.isEmpty()) return true
    if (expected.size > size) return false
    return (0..size - expected.size).any { start ->
        expected.indices.all { this[start + it] == expected[it] }
    }
}
