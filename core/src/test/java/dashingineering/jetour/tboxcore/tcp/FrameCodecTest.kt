package dashingineering.jetour.tboxcore.tcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    @Test
    fun typedRoundTrip() {
        val frame = FrameCodec.encode(byteArrayOf(1, 2, 3), FrameCodec.TYPE_STATUS)
        val result = FrameCodec.decode(frame) as FrameCodec.DecodeResult.Success
        assertEquals(FrameCodec.TYPE_STATUS, result.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.data)
        assertEquals(frame.size, result.consumed)
    }

    @Test
    fun decodeRespectsLimitInsteadOfBufferSize() {
        val frame = FrameCodec.encode(byteArrayOf(9, 9, 9, 9))
        val buffer = ByteArray(1024)
        System.arraycopy(frame, 0, buffer, 0, frame.size)
        val partial = FrameCodec.decode(buffer, 0, frame.size - 1)
        assertTrue(partial is FrameCodec.DecodeResult.Incomplete)
        val full = FrameCodec.decode(buffer, 0, frame.size)
        assertTrue(full is FrameCodec.DecodeResult.Success)
    }

    @Test
    fun legacyFrameHasNoTypeByte() {
        val frame = FrameCodec.encodeLegacy(byteArrayOf(0x8E.toByte(), 0x5D))
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x8E.toByte(), 0x5D), frame)
    }

    @Test
    fun readerReturnsAllFramesFromOneChunk() {
        val chunk = FrameCodec.encodeLegacy(byteArrayOf(1)) +
            FrameCodec.encodeLegacy(byteArrayOf(2, 2)) +
            FrameCodec.encodeLegacy(byteArrayOf(3, 3, 3))
        val bodies = FrameReader().append(chunk)
        assertEquals(3, bodies.size)
        assertArrayEquals(byteArrayOf(3, 3, 3), bodies[2])
    }

    @Test
    fun readerReassemblesByteByByte() {
        val frames = FrameCodec.encode(ByteArray(300) { it.toByte() }) + FrameCodec.encode(byteArrayOf(7))
        val reader = FrameReader(initialCapacity = 4)
        val bodies = frames.flatMap { reader.append(byteArrayOf(it)) }
        assertEquals(2, bodies.size)
        assertEquals(301, bodies[0].size)
        assertArrayEquals(byteArrayOf(FrameCodec.TYPE_DATA, 7), bodies[1])
    }

    @Test(expected = FrameReader.FrameException::class)
    fun readerRejectsInvalidLength() {
        FrameReader().append(byteArrayOf(0x7F, 0, 0, 0, 1))
    }
}
