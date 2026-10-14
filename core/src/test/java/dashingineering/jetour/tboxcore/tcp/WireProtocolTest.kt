package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.TBoxProxyInfo
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireProtocolTest {
    private val packet = byteArrayOf(0x8E.toByte(), 0x5D, 0x00, 0x0E)

    @Test
    fun helloRoundTrip() {
        val hello = WireProtocol.decodeHello(WireProtocol.encodeHello("vad.dashing.tbox"))!!
        assertEquals(TBoxProxyInfo.PROTOCOL_VERSION, hello.protocolVersion)
        assertEquals("vad.dashing.tbox", hello.clientPackage)
        assertEquals(TBoxProxyInfo.LIBRARY_VERSION, hello.libraryVersion)
    }

    @Test
    fun hostInfoRoundTripIgnoresTrailingFields() {
        val info = TBoxHostInfo(TBoxWireProtocol.V3, "pkg", "3.0.0", networkBound = true, networkInterface = "usb0")
        val encoded = WireProtocol.encodeHostInfo(info) + byteArrayOf(1, 2, 3)
        assertEquals(info, WireProtocol.decodeHostInfo(encoded))
    }

    @Test
    fun truncatedHostInfoIsRejected() {
        assertNull(WireProtocol.decodeHostInfo(byteArrayOf(3, 0)))
    }

    @Test
    fun legacyClientFormatDetection() {
        assertEquals(TBoxWireProtocol.LEGACY_V1, WireProtocol.detectLegacyClientFormat(packet))
        assertEquals(
            TBoxWireProtocol.TYPED_V2,
            WireProtocol.detectLegacyClientFormat(byteArrayOf(FrameCodec.TYPE_DATA) + packet)
        )
        assertEquals(TBoxWireProtocol.LEGACY_V1, WireProtocol.detectLegacyClientFormat(byteArrayOf(0x42)))
    }

    @Test
    fun legacyHostFormatDetection() {
        assertEquals(TBoxWireProtocol.LEGACY_V1, WireProtocol.detectLegacyHostFormat(packet))
        assertEquals(
            TBoxWireProtocol.TYPED_V2,
            WireProtocol.detectLegacyHostFormat(byteArrayOf(FrameCodec.TYPE_DATA) + packet)
        )
        val status = byteArrayOf(FrameCodec.TYPE_STATUS) + TBoxStatus(TBoxStatusType.LOG, "x").toByteArray()
        assertEquals(TBoxWireProtocol.TYPED_V2, WireProtocol.detectLegacyHostFormat(status))
        assertNull(WireProtocol.detectLegacyHostFormat(byteArrayOf(0x42)))
    }

    @Test
    fun newStatusTypesAreAppendedAfterV2Range() {
        assertEquals(12, TBoxStatusType.LAST_V2_STATUS.ordinal)
        assertEquals(TBoxStatusType.LOG, TBoxStatusType.entries[12])
    }
}
