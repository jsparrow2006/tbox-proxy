package dashingineering.jetour.tboxcore

import dashingineering.jetour.tboxcore.tcp.BridgeConnection
import dashingineering.jetour.tboxcore.tcp.FrameCodec
import dashingineering.jetour.tboxcore.tcp.FrameReader
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.util.ByteConverter
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal val testLog: (LogType, String, String) -> Unit = { type, tag, message ->
    if (type != LogType.DEBUG) println("[$type] $tag: $message")
}

internal fun tboxPacket(tid: Int, cmd: Int, vararg payload: Byte): ByteArray {
    val header = ByteConverter.fillHeader(payload.size, tid.toByte(), 0x50, cmd.toByte())
    val body = header + payload
    return body + ByteConverter.xorSum(body)
}

internal fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

internal fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

internal fun <T> LinkedBlockingQueue<T>.awaitMatching(timeoutMs: Long = 3_000, predicate: (T) -> Boolean): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val left = deadline - System.currentTimeMillis()
        check(left > 0) { "Timed out waiting for matching item" }
        val item = poll(left, TimeUnit.MILLISECONDS) ?: continue
        if (predicate(item)) return item
    }
}

/** Ждёт все [expected] пакеты в любом порядке, остальные пропускает. */
internal fun LinkedBlockingQueue<ByteArray>.awaitAll(expected: List<ByteArray>, timeoutMs: Long = 3_000) {
    val missing = expected.toMutableList()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (missing.isNotEmpty()) {
        val left = deadline - System.currentTimeMillis()
        check(left > 0) { "Timed out, ${missing.size} packets missing" }
        val item = poll(left, TimeUnit.MILLISECONDS) ?: continue
        missing.removeAll { it.contentEquals(item) }
    }
}

/** UDP-имитация TBox: запоминает пакеты и отвечает последнему отправителю. */
internal class FakeTBox : Closeable {
    val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val received = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var lastSender: SocketAddress? = null

    private val reader = thread(isDaemon = true) {
        val buffer = ByteArray(65_535)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                lastSender = packet.socketAddress
                received += buffer.copyOf(packet.length)
            } catch (_: Exception) {
            }
        }
    }

    val port: Int get() = socket.localPort

    fun reply(data: ByteArray) {
        socket.send(DatagramPacket(data, data.size, checkNotNull(lastSender) { "No sender yet" }))
    }

    override fun close() = socket.close()
}

/** Клиент 1.x (`typed = false`) или 2.1.x (`typed = true`) — шлёт и читает кадры как старые версии. */
internal class RawLegacyClient(port: Int, private val typed: Boolean) : Closeable {
    private val socket = Socket("127.0.0.1", port)
    val bodies = LinkedBlockingQueue<ByteArray>()

    private val reader = thread(isDaemon = true) {
        val frames = FrameReader()
        val chunk = ByteArray(4096)
        try {
            val input = socket.getInputStream()
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                frames.append(chunk, read).forEach { bodies += it }
            }
        } catch (_: Exception) {
        }
    }

    fun frameFor(packet: ByteArray): ByteArray =
        if (typed) FrameCodec.encode(packet, FrameCodec.TYPE_DATA) else FrameCodec.encodeLegacy(packet)

    fun writeRaw(bytes: ByteArray) {
        socket.getOutputStream().apply {
            write(bytes)
            flush()
        }
    }

    fun send(packet: ByteArray) = writeRaw(frameFor(packet))

    override fun close() = socket.close()
}

internal class CollectingListener : BridgeConnection.Listener {
    val data = LinkedBlockingQueue<ByteArray>()
    val statuses = LinkedBlockingQueue<TBoxStatus>()
    val hostInfos = LinkedBlockingQueue<TBoxHostInfo>()
    val closed = LinkedBlockingQueue<String>()

    override fun onData(data: ByteArray) {
        this.data += data
    }

    override fun onStatus(status: TBoxStatus) {
        statuses += status
    }

    override fun onHostInfo(info: TBoxHostInfo) {
        hostInfos += info
    }

    override fun onClosed(reason: String) {
        closed += reason
    }
}
