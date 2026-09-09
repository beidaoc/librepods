/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.bluetooth

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ATTHandles(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A)
}

enum class ATTCCCDHandles(val value: Int) {
    TRANSPARENCY(ATTHandles.TRANSPARENCY.value + 1),
    //    LOUD_SOUND_REDUCTION(ATTHandles.LOUD_SOUND_REDUCTION.value + 1), // doesn't work
    HEARING_AID(ATTHandles.HEARING_AID.value + 1)
}

/** One ATT bearer. Closing it must also unblock connect() and input.read(). */
interface AttTransport : Closeable {
    val input: InputStream
    val output: OutputStream
    fun connect()
}

class ATTManagerv2(
    private val log: (String) -> Unit = {},
    private val retryDelaysMillis: List<Long> = listOf(1_000, 2_000, 4_000),
    private val connectTimeoutMillis: Long = 3_000,
) : Closeable {
    private class Pending(val requestOpcode: Byte, val responseOpcode: Byte) {
        val responses = LinkedBlockingQueue<ByteArray>(1)
    }

    private class Session(val transport: AttTransport) {
        val closed = AtomicBoolean(false)
        val requestLock = Any()
        @Volatile var pending: Pending? = null

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            pending?.responses?.offer(byteArrayOf())
            runCatching { transport.close() }
        }
    }

    private class Connection(
        val key: Any,
        val factory: () -> AttTransport,
        val isValid: () -> Boolean,
    ) {
        val stopped = CountDownLatch(1)
        @Volatile var transport: AttTransport? = null
        @Volatile var session: Session? = null
    }

    private val lock = Any()
    private var connection: Connection? = null
    private var destroyed = false
    private val characteristicList = mutableMapOf<ATTHandles, ByteArray>()
    private val notifications = ConcurrentHashMap.newKeySet<ATTCCCDHandles>()
    @Volatile private var onNotificationReceived: ((Byte, ByteArray) -> Unit)? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ATT-ConnectTimeout").apply { isDaemon = true }
    }

    val isConnected: Boolean
        get() = synchronized(lock) { connection?.session?.closed?.get() == false }

    /** Retry only this AACP session; never reconnect the device or claim audio ownership. */
    fun connect(key: Any, factory: () -> AttTransport, isValid: () -> Boolean) {
        val next = Connection(key, factory, isValid)
        val previous = synchronized(lock) {
            if (destroyed) return
            if (connection?.key === key) return
            val old = connection
            old?.stopped?.countDown()
            connection = next
            characteristicList.clear()
            old
        }
        retire(previous)
        Thread({ runConnection(next) }, "ATT-Connection").apply { isDaemon = true }.start()
    }

    /** A stale AACP reader must not tear down a newer ATT connection. */
    fun disconnected(key: Any? = null) {
        val previous = synchronized(lock) {
            val old = connection ?: return
            if (key != null && old.key !== key) return
            old.stopped.countDown()
            connection = null
            characteristicList.clear()
            old
        }
        retire(previous)
        log("event=att_stopped")
    }

    override fun close() {
        synchronized(lock) { destroyed = true }
        disconnected()
        watchdog.shutdownNow()
    }

    private fun retire(owner: Connection?) {
        if (owner == null) return
        owner.stopped.countDown()
        owner.session?.close()
        runCatching { owner.transport?.close() }
    }

    private fun valid(owner: Connection): Boolean = synchronized(lock) {
        connection === owner && owner.stopped.count == 1L && !destroyed && owner.isValid()
    }

    private fun current(session: Session): Boolean = synchronized(lock) {
        !session.closed.get() && connection?.let { it.session === session && valid(it) } == true
    }

    private fun runConnection(owner: Connection) {
        // The budget is per AACP session, including repeated short-lived ATT connections.
        for (attempt in 0..retryDelaysMillis.size) {
            if (attempt > 0) {
                val delay = retryDelaysMillis[attempt - 1]
                log("event=att_retry attempt=$attempt delayMs=$delay")
                if (owner.stopped.await(delay, TimeUnit.MILLISECONDS)) return
            }
            if (!valid(owner)) return
            var transport: AttTransport? = null
            var session: Session? = null
            try {
                val candidate = owner.factory()
                transport = candidate
                synchronized(lock) {
                    if (!valid(owner)) return
                    owner.transport = candidate
                }
                // Coroutine timeout alone cannot interrupt BluetoothSocket.connect().
                val connecting = AtomicBoolean(true)
                val timeout = watchdog.schedule({
                    if (connecting.compareAndSet(true, false)) {
                        log("event=att_connect_timeout")
                        runCatching { candidate.close() }
                    }
                }, connectTimeoutMillis, TimeUnit.MILLISECONDS)
                try {
                    candidate.connect()
                    if (!connecting.compareAndSet(true, false)) throw IOException("ATT connect timed out")
                } finally {
                    connecting.set(false)
                    timeout.cancel(false)
                }
                val connected = Session(candidate)
                session = connected
                synchronized(lock) {
                    if (!valid(owner)) return
                    owner.session = connected
                }
                log("event=att_connected attempt=$attempt")
                Thread({ initialize(connected) }, "ATT-Initialize").apply { isDaemon = true }.start()
                readLoop(connected)
            } catch (e: Exception) {
                if (valid(owner)) log("event=att_transport_failed error=${e.javaClass.simpleName}")
            } finally {
                session?.close()
                runCatching { transport?.close() }
                synchronized(lock) {
                    if (owner.session === session) owner.session = null
                    if (owner.transport === transport) owner.transport = null
                    if (connection === owner) characteristicList.clear()
                }
            }
        }
        if (valid(owner)) log("event=att_retry_exhausted; AACP left unchanged")
    }

    private fun initialize(session: Session) {
        for (handle in ATTHandles.entries) {
            val value = readCharacteristic(session, handle, 2_000)
            if (!current(session)) return
            if (value != null) notifyValue(handle.value.toByte(), value)
        }
        for (handle in notifications) {
            request(session, byteArrayOf(0x12, handle.value.toByte(), 0x00, 0x01), 0x13, 2_000)
        }
    }

    fun setOnNotificationReceived(listener: ((Byte, ByteArray) -> Unit)?) {
        onNotificationReceived = listener
    }

    fun enableNotification(handle: ATTCCCDHandles) {
        notifications.add(handle)
        writeCharacteristic(handle.value.toByte(), byteArrayOf(0x01))
    }

    fun getCharacteristic(handle: ATTHandles): ByteArray? {
        val stored = synchronized(lock) { characteristicList[handle]?.copyOf() }
        return stored ?: readCharacteristic(handle)
    }

    fun readCharacteristic(handle: ATTHandles, timeoutMillis: Long = 2_000): ByteArray? {
        val session = synchronized(lock) { connection?.session } ?: return null
        return readCharacteristic(session, handle, timeoutMillis)
    }

    private fun readCharacteristic(session: Session, handle: ATTHandles, timeoutMillis: Long): ByteArray? =
        synchronized(session.requestLock) {
            val response = request(session, byteArrayOf(0x0A, handle.value.toByte(), 0x00), 0x0B, timeoutMillis)
                ?: return null
            val value = response.copyOfRange(1, response.size)
            synchronized(lock) {
                if (!current(session)) return null
                characteristicList[handle] = value
            }
            value.copyOf()
        }

    fun writeCharacteristic(handle: ATTHandles, data: ByteArray, timeoutMillis: Long = 2_000) {
        val session = synchronized(lock) { connection?.session } ?: return
        synchronized(session.requestLock) {
            val value = data.copyOf()
            request(session, byteArrayOf(0x12, handle.value.toByte(), 0x00) + value, 0x13, timeoutMillis)
                ?: return
            synchronized(lock) {
                if (current(session)) characteristicList[handle] = value
            }
        }
    }

    fun writeCharacteristic(handle: Byte, data: ByteArray, timeoutMillis: Long = 2_000) {
        val session = synchronized(lock) { connection?.session } ?: return
        request(session, byteArrayOf(0x12, handle, 0x00) + data, 0x13, timeoutMillis)
    }

    private fun request(session: Session, pdu: ByteArray, responseOpcode: Byte, timeoutMillis: Long): ByteArray? =
        synchronized(session.requestLock) {
            if (!current(session)) return null
            val pending = Pending(pdu[0], responseOpcode)
            session.pending = pending
            try {
                if (!current(session)) return null
                session.transport.output.write(pdu)
                session.transport.output.flush()
                val response = pending.responses.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!current(session)) return null
                if (response == null) {
                    // A late response has no transaction ID. Do not let it satisfy the next request.
                    invalidate(session, "response_timeout")
                    return null
                }
                if (response.isEmpty()) return null
                if (response[0] == 0x01.toByte()) {
                    log("event=att_request_rejected opcode=${pdu[0]} error=${response.last()}")
                    return null
                }
                return response
            } catch (e: Exception) {
                invalidate(session, "request_${e.javaClass.simpleName}")
                return null
            } finally {
                session.pending = null
            }
        }

    private fun invalidate(session: Session, reason: String) {
        synchronized(lock) {
            if (connection?.session !== session || session.closed.get()) return
            characteristicList.clear()
        }
        log("event=att_transport_closed reason=$reason")
        session.close()
    }

    private fun readLoop(session: Session) {
        val buffer = ByteArray(512)
        while (current(session)) {
            val len = session.transport.input.read(buffer)
            if (len < 0) {
                invalidate(session, "eof")
                return
            }
            if (len == 0 || !current(session)) continue
            val data = buffer.copyOf(len)
            val pending = session.pending
            if (pending != null && (data[0] == pending.responseOpcode ||
                    (data[0] == 0x01.toByte() && data.size >= 5 && data[1] == pending.requestOpcode))) {
                pending.responses.offer(data)
            }
            if (data[0] == 0x1B.toByte() && data.size >= 3 && current(session)) {
                notifyValue(data[1], data.copyOfRange(3, data.size))
            }
        }
    }

    private fun notifyValue(handle: Byte, value: ByteArray) {
        try {
            onNotificationReceived?.invoke(handle, value)
        } catch (e: Exception) {
            log("event=att_notification_failed error=${e.javaClass.simpleName}")
        }
    }
}
