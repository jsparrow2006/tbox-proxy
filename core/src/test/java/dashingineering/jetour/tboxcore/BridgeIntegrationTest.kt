package dashingineering.jetour.tboxcore

import dashingineering.jetour.tboxcore.service.TBoxBridge
import dashingineering.jetour.tboxcore.tcp.BridgeConnection
import dashingineering.jetour.tboxcore.tcp.FrameCodec
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class BridgeIntegrationTest {
    private lateinit var tbox: FakeTBox
    private lateinit var bridge: TBoxBridge
    private lateinit var config: TBoxBridge.Config
    private val closeables = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
        tbox = FakeTBox()
        config = TBoxBridge.Config(
            localPort = freeUdpPort(),
            remotePort = tbox.port,
            remoteAddress = InetAddress.getLoopbackAddress(),
            legacyTcpPort = freeTcpPort(),
            v3TcpPort = freeTcpPort(),
            hostPackage = "test.host"
        )
        bridge = TBoxBridge(config, testLog)
        assertEquals(TBoxBridge.StartResult.STARTED, bridge.start())
    }

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        bridge.stop()
        tbox.close()
    }

    private fun <T : AutoCloseable> T.track(): T = also { closeables += it }

    private fun connectV3(listener: CollectingListener): BridgeConnection = runBlocking {
        checkNotNull(BridgeConnection.connectV3("127.0.0.1", config.v3TcpPort, "test.client", testLog, listener))
    }.also { connection -> closeables += AutoCloseable { connection.close() } }

    @Test
    fun clientsOfAllVersionsShareOneBridge() = runBlocking {
        val v1 = RawLegacyClient(config.legacyTcpPort, typed = false).track()
        val v2 = RawLegacyClient(config.legacyTcpPort, typed = true).track()
        val silentV1 = RawLegacyClient(config.legacyTcpPort, typed = false).track()
        val v3Listener = CollectingListener()
        val v3 = connectV3(v3Listener)

        val hostInfo = v3.hostInfo
        assertNotNull(hostInfo)
        assertEquals(TBoxWireProtocol.V3, hostInfo!!.protocol)
        assertEquals("test.host", hostInfo.hostPackage)
        assertFalse(hostInfo.networkBound)

        val fromV1 = tboxPacket(0x25, 0x07, 1)
        val fromV2 = tboxPacket(0x2D, 0x01, 2)
        val fromV3 = tboxPacket(0x23, 0x15, 3)
        v1.send(fromV1)
        v2.send(fromV2)
        assertTrue(v3.send(fromV3))
        tbox.received.awaitAll(listOf(fromV1, fromV2, fromV3))

        val answer = tboxPacket(0x50, 0x87, 9, 9)
        tbox.reply(answer)
        assertArrayEquals(answer, v1.bodies.awaitMatching { it[0] != FrameCodec.TYPE_STATUS })
        assertArrayEquals(answer, silentV1.bodies.awaitMatching { true })
        assertArrayEquals(
            byteArrayOf(FrameCodec.TYPE_DATA) + answer,
            v2.bodies.awaitMatching { it[0] == FrameCodec.TYPE_DATA }
        )
        assertArrayEquals(answer, v3Listener.data.awaitMatching { true })
    }

    @Test
    fun statusesAreFilteredPerClientVersion() = runBlocking {
        val v1 = RawLegacyClient(config.legacyTcpPort, typed = false).track()
        val v2 = RawLegacyClient(config.legacyTcpPort, typed = true).track()
        val v3Listener = CollectingListener()
        connectV3(v3Listener)
        v1.send(tboxPacket(0x2D, 0x01))
        v2.send(tboxPacket(0x2D, 0x01))
        tbox.received.awaitMatching { true }
        Thread.sleep(200)

        bridge.onNetworkChanged(null, null)
        bridge.forwardLog(LogType.INFO, "Test", "marker")

        v3Listener.statuses.awaitMatching { it.type == TBoxStatusType.NETWORK_UNBOUND }
        val v2Log = v2.bodies.awaitMatching { it[0] == FrameCodec.TYPE_STATUS && statusOf(it).type == TBoxStatusType.LOG }
        assertTrue(statusOf(v2Log).message.contains("marker"))
        v2.bodies.forEach { body ->
            if (body[0] == FrameCodec.TYPE_STATUS) {
                assertTrue(statusOf(body).type.ordinal <= TBoxStatusType.LAST_V2_STATUS.ordinal)
            }
        }
        assertTrue("1.x client must not get status frames", v1.bodies.none { it[0] != 0x8E.toByte() })
    }

    @Test
    fun backToBackFramesInOneWriteAreAllForwarded() {
        val v1 = RawLegacyClient(config.legacyTcpPort, typed = false).track()
        val first = tboxPacket(0x25, 0x07, 1)
        val second = tboxPacket(0x25, 0x11, 2)
        v1.writeRaw(v1.frameFor(first) + v1.frameFor(second))
        tbox.received.awaitAll(listOf(first, second))
    }

    @Test
    fun secondBridgeCannotTakeBusyPorts() {
        val second = TBoxBridge(config, testLog)
        assertEquals(TBoxBridge.StartResult.UDP_BIND_FAILED, second.start())
        second.stop()
    }

    @Test
    fun v3ClientSeesNetworkChangeInHostInfo() {
        val listener = CollectingListener()
        connectV3(listener)
        listener.hostInfos.awaitMatching { true }
        var configured = false
        bridge.onNetworkChanged({ configured = true }, "usb0")
        val info = listener.hostInfos.awaitMatching { it.networkBound }
        assertEquals("usb0", info.networkInterface)
        assertTrue(configured)
        listener.statuses.awaitMatching { it.type == TBoxStatusType.NETWORK_BOUND }
    }

    private fun statusOf(body: ByteArray): TBoxStatus =
        checkNotNull(TBoxStatus.fromByteArray(body.copyOfRange(1, body.size)))
}
