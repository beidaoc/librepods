package me.kavishdevar.librepods.bluetooth

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ATTManagerRecoveryTest {
    private class Transport(val value: Byte = 1, val unblockOnClose: Boolean = true) : AttTransport {
        val packets = LinkedBlockingQueue<ByteArray>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val closed = AtomicBoolean(false)
        val connecting = CountDownLatch(1)
        var connectGate: CountDownLatch? = null
        @Volatile var respond = true
        @Volatile var failWrites = false
        @Volatile var rejectWrites = false

        override fun connect() {
            connecting.countDown()
            connectGate?.await(3, TimeUnit.SECONDS)
            if (closed.get()) throw IOException("closed")
        }
        override val input = object : InputStream() {
            override fun read(): Int = error("packet reads required")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val packet = packets.take()
                if (packet.isEmpty()) return -1
                packet.copyInto(buffer, off)
                return packet.size
            }
        }
        override val output = object : OutputStream() {
            override fun write(value: Int) = error("packet writes required")
            override fun write(data: ByteArray, off: Int, len: Int) {
                if (closed.get() || failWrites) throw IOException("Broken pipe")
                val pdu = data.copyOfRange(off, off + len)
                writes.add(pdu)
                if (respond) {
                    packets.offer(when {
                        pdu[0] == 0x0A.toByte() -> byteArrayOf(0x0B, value)
                        rejectWrites -> byteArrayOf(0x01, pdu[0], pdu[1], 0, 0x03)
                        else -> byteArrayOf(0x13)
                    })
                }
            }
        }
        override fun close() {
            closed.set(true)
            if (unblockOnClose) {
                packets.offer(byteArrayOf())
                connectGate?.countDown()
            }
        }
        fun eof() { packets.offer(byteArrayOf()) }
        fun writeCount() = writes.count { it[0] == 0x12.toByte() }
    }

    private fun manager(log: (String) -> Unit = {}) = ATTManagerv2(log, listOf(10, 10, 10), 200)
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition not reached", condition())
    }
    private fun ready(transport: Transport) {
        awaitCondition { transport.writes.count { it[0] == 0x0A.toByte() } == 3 }
    }

    @Test fun eofRecoversOnlyAttAndRestoresReadsAndSubscriptions() {
        val first = Transport(1)
        val second = Transport(2)
        val opens = AtomicInteger()
        manager().use { manager ->
            manager.connect(Any(), { if (opens.getAndIncrement() == 0) first else second }, { true })
            ready(first)
            manager.enableNotification(ATTCCCDHandles.TRANSPARENCY)
            first.eof()
            ready(second)
            awaitCondition { second.writeCount() == 1 }
            assertTrue(first.closed.get())
            assertEquals(2, opens.get())
            assertArrayEquals(byteArrayOf(2), manager.getCharacteristic(ATTHandles.TRANSPARENCY))
            manager.writeCharacteristic(ATTHandles.HEARING_AID, byteArrayOf(9))
            assertArrayEquals(byteArrayOf(9), manager.getCharacteristic(ATTHandles.HEARING_AID))
        }
    }

    @Test fun brokenPipeInvalidatesTransportAndDoesNotCacheFailedWrite() {
        val first = Transport(1)
        val second = Transport(2)
        val opens = AtomicInteger()
        manager().use { manager ->
            manager.connect(Any(), { if (opens.getAndIncrement() == 0) first else second }, { true })
            ready(first)
            first.failWrites = true
            manager.writeCharacteristic(ATTHandles.TRANSPARENCY, byteArrayOf(9))
            ready(second)
            assertTrue(first.closed.get())
            assertArrayEquals(byteArrayOf(2), manager.getCharacteristic(ATTHandles.TRANSPARENCY))
        }
    }

    @Test fun retriesAreBoundedEvenWhenConnectionsImmediatelyEnd() {
        val opens = AtomicInteger()
        val exhausted = CountDownLatch(1)
        manager { if (it.contains("att_retry_exhausted")) exhausted.countDown() }.use { manager ->
            manager.connect(Any(), {
                opens.incrementAndGet()
                Transport().apply { eof() }
            }, { true })
            assertTrue(exhausted.await(3, TimeUnit.SECONDS))
            assertEquals(4, opens.get())
        }
    }

    @Test fun aacpLossPreventsRetry() {
        val first = Transport()
        val valid = AtomicBoolean(true)
        val opens = AtomicInteger()
        val retry = CountDownLatch(1)
        manager { if (it.contains("att_retry attempt=")) retry.countDown() }.use { manager ->
            manager.connect(Any(), { opens.incrementAndGet(); first }, { valid.get() })
            ready(first)
            valid.set(false)
            first.eof()
            assertTrue(retry.await(3, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(1, opens.get())
            assertTrue(first.closed.get())
        }
    }

    @Test fun staleReaderAndDisconnectCannotTearDownReplacement() {
        val first = Transport(unblockOnClose = false)
        val second = Transport(2)
        val oldKey = Any()
        manager().use { manager ->
            manager.connect(oldKey, { first }, { true })
            ready(first)
            manager.connect(Any(), { second }, { true })
            ready(second)
            first.eof()
            manager.disconnected(oldKey)
            manager.writeCharacteristic(ATTHandles.TRANSPARENCY, byteArrayOf(7))
            assertFalse(second.closed.get())
            assertArrayEquals(byteArrayOf(7), manager.getCharacteristic(ATTHandles.TRANSPARENCY))
        }
    }

    @Test fun connectTimeoutClosesBlockingSocketBeforeRetry() {
        val first = Transport().apply { connectGate = CountDownLatch(1) }
        val second = Transport(2)
        val opens = AtomicInteger()
        ATTManagerv2({}, listOf(10), 30).use { manager ->
            manager.connect(Any(), { if (opens.getAndIncrement() == 0) first else second }, { true })
            ready(second)
            assertTrue(first.closed.get())
            assertEquals(2, opens.get())
        }
    }

    @Test fun disconnectDuringConnectClosesCandidateAndCancelsRetry() {
        val candidate = Transport().apply { connectGate = CountDownLatch(1) }
        val opens = AtomicInteger()
        manager().use { manager ->
            manager.connect(Any(), { opens.incrementAndGet(); candidate }, { true })
            assertTrue(candidate.connecting.await(3, TimeUnit.SECONDS))
            manager.disconnected()
            awaitCondition { candidate.closed.get() }
            Thread.sleep(50)
            assertEquals(1, opens.get())
            assertTrue(candidate.writes.isEmpty())
        }
    }

    @Test fun requestsAreSerializedAndEofWakesPendingRequest() {
        val transport = Transport()
        manager().use { manager ->
            manager.connect(Any(), { transport }, { true })
            ready(transport)
            // Drain bootstrap requests before withholding responses.
            manager.readCharacteristic(ATTHandles.TRANSPARENCY)
            transport.respond = false
            val a = thread { manager.writeCharacteristic(ATTHandles.TRANSPARENCY, byteArrayOf(3)) }
            awaitCondition { transport.writeCount() == 1 }
            val b = thread { manager.writeCharacteristic(ATTHandles.HEARING_AID, byteArrayOf(4)) }
            Thread.sleep(30)
            assertEquals(1, transport.writeCount())
            transport.packets.offer(byteArrayOf(0x13))
            awaitCondition { transport.writeCount() == 2 }
            manager.disconnected()
            a.join(500)
            b.join(500)
            assertFalse(a.isAlive)
            assertFalse(b.isAlive)
            assertNull(manager.getCharacteristic(ATTHandles.TRANSPARENCY))
        }
    }

    @Test fun protocolRejectionDoesNotReconnectOrPretendWriteSucceeded() {
        val transport = Transport()
        val opens = AtomicInteger()
        manager().use { manager ->
            manager.connect(Any(), { opens.incrementAndGet(); transport }, { true })
            ready(transport)
            transport.rejectWrites = true
            manager.writeCharacteristic(ATTHandles.TRANSPARENCY, byteArrayOf(9))
            assertArrayEquals(byteArrayOf(1), manager.getCharacteristic(ATTHandles.TRANSPARENCY))
            assertEquals(1, opens.get())
            assertFalse(transport.closed.get())
        }
    }

    @Test fun timedOutRequestCannotFeedLateResponseIntoNewSession() {
        val first = Transport(1)
        val second = Transport(2)
        val opens = AtomicInteger()
        manager().use { manager ->
            manager.connect(Any(), { if (opens.getAndIncrement() == 0) first else second }, { true })
            ready(first)
            manager.readCharacteristic(ATTHandles.TRANSPARENCY)
            first.respond = false
            assertNull(manager.readCharacteristic(ATTHandles.TRANSPARENCY, 20))
            first.packets.offer(byteArrayOf(0x0B, 99))
            ready(second)
            assertArrayEquals(byteArrayOf(2), manager.getCharacteristic(ATTHandles.TRANSPARENCY))
        }
    }
}
