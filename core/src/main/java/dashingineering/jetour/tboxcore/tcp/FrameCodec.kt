package dashingineering.jetour.tboxcore.tcp

import java.nio.ByteBuffer

object FrameCodec {
    const val TYPE_DATA: Byte = 0x01
    const val TYPE_STATUS: Byte = 0x02
    private const val MAX_FRAME_SIZE = 64 * 1024

    fun encode(payload: ByteArray, type: Byte = TYPE_DATA): ByteArray {
        val totalLength = 1 + payload.size
        require(totalLength <= MAX_FRAME_SIZE) {
            "Payload too large: ${payload.size} > ${MAX_FRAME_SIZE - 1}"
        }
        return ByteBuffer.allocate(4 + totalLength).apply {
            putInt(totalLength)
            put(type)
            put(payload)
        }.array()
    }

    fun decode(buffer: ByteArray, offset: Int = 0): DecodeResult {
        if (buffer.size - offset < 4) {
            return DecodeResult.Incomplete(4)
        }

        val length = ByteBuffer.wrap(buffer, offset, 4).int

        if (length < 1 || length > MAX_FRAME_SIZE) {
            return DecodeResult.Error("Invalid frame length: $length")
        }

        val totalNeeded = 4 + length
        if (buffer.size - offset < totalNeeded) {
            return DecodeResult.Incomplete(totalNeeded)
        }

        val type = buffer[offset + 4]
        val payloadLength = length - 1
        val payload = ByteArray(payloadLength)
        System.arraycopy(buffer, offset + 5, payload, 0, payloadLength)

        return DecodeResult.Success(payload, totalNeeded, type)
    }

    sealed class DecodeResult {
        data class Success(val data: ByteArray, val consumed: Int, val type: Byte = TYPE_DATA) : DecodeResult()
        data class Incomplete(val neededBytes: Int) : DecodeResult()
        data class Error(val message: String) : DecodeResult()
    }
}
