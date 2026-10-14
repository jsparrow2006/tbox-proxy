package dashingineering.jetour.tboxcore

import dashingineering.jetour.tboxcore.tcp.BridgeConnection
import dashingineering.jetour.tboxcore.tcp.FrameCodec
import dashingineering.jetour.tboxcore.tcp.FrameReader
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Новый клиент против хостов 1.x и 2.1.x (их TCP-поведение воспроизведено по исходникам). */
class LegacyHostCompatTest {

    private class FakeLegacyHost(private val typed: Boolean, greetWithStatus: Boolean) : Closeable {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val bodies = LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var client: Socket? = null

        private val acceptor = thread(isDaemon = true) {
            try {
                val socket = server.accept()
                client = socket
                if (greetWithStatus) {
                    val status = TBoxStatus(TBoxStatusType.LOG, "[TcpServer] Total clients: 1").toByteArray()
                    socket.getOutputStream().write(FrameCodec.encode(status, FrameCodec.TYPE_STATUS))
                }
                val reader = FrameReader()
                val chunk = ByteArray(4096)
                val input = socket.getInputStream()
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    reader.append(chunk, read).forEach { bodies += it }
                }
            } catch (_: Exception) {
            }
        }

        fun sendData(packet: ByteArray) {
            val frame = if (typed) FrameCodec.encode(packet, FrameCodec.TYPE_DATA) else FrameCodec.encodeLegacy(packet)
            checkNotNull(client).getOutputStream().apply {
                write(frame)
                flush()
            }
        }

        override fun close() = server.close().also { client?.close() }
    }

    @Test
    fun silentV1HostDefaultsToLegacyFormat() = runBlocking {
        FakeLegacyHost(typed = false, greetWithStatus = false).use { host ->
            val listener = CollectingListener()
            val connection = checkNotNull(
                BridgeConnection.connectLegacy("127.0.0.1", host.port, testLog, listener, detectTimeoutMs = 300)
            )
            assertNull(connection.hostInfo)
            val command = tboxPacket(0x25, 0x07)
            connection.send(command)
            assertArrayEquals(command, host.bodies.awaitMatching { true })
            assertEquals(TBoxWireProtocol.LEGACY_V1, connection.hostInfo?.protocol)

            val answer = tboxPacket(0x50, 0x87, 1)
            host.sendData(answer)
            assertArrayEquals(answer, listener.data.awaitMatching { true })
            connection.close()
        }
    }

    @Test
    fun v1HostDetectedByFirstDataFrame() = runBlocking {
        FakeLegacyHost(typed = false, greetWithStatus = false).use { host ->
            val listener = CollectingListener()
            val connection = checkNotNull(BridgeConnection.connectLegacy("127.0.0.1", host.port, testLog, listener))
            Thread.sleep(100)
            val answer = tboxPacket(0x50, 0x87, 1)
            host.sendData(answer)
            assertArrayEquals(answer, listener.data.awaitMatching { true })
            assertEquals(TBoxWireProtocol.LEGACY_V1, listener.hostInfos.awaitMatching { true }.protocol)
            connection.close()
        }
    }

    @Test
    fun v21HostDetectedByStatusGreeting() = runBlocking {
        FakeLegacyHost(typed = true, greetWithStatus = true).use { host ->
            val listener = CollectingListener()
            val connection = checkNotNull(BridgeConnection.connectLegacy("127.0.0.1", host.port, testLog, listener))
            assertEquals(TBoxWireProtocol.TYPED_V2, listener.hostInfos.awaitMatching { true }.protocol)
            listener.statuses.awaitMatching { it.type == TBoxStatusType.LOG }

            val command = tboxPacket(0x23, 0x15)
            connection.send(command)
            assertArrayEquals(byteArrayOf(FrameCodec.TYPE_DATA) + command, host.bodies.awaitMatching { true })

            val answer = tboxPacket(0x50, 0x95, 5)
            host.sendData(answer)
            assertArrayEquals(answer, listener.data.awaitMatching { true })
            connection.close()
        }
    }

    @Test
    fun v3HandshakeTimesOutWhenPeerIsNotV3() = runBlocking {
        FakeLegacyHost(typed = false, greetWithStatus = false).use { host ->
            val connection = BridgeConnection.connectV3(
                "127.0.0.1", host.port, "test.client", testLog, CollectingListener(), handshakeTimeoutMs = 300
            )
            assertNull(connection)
        }
    }
}
