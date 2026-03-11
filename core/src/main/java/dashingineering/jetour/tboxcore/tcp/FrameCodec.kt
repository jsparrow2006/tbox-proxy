package dashingineering.jetour.tboxcore.tcp

import java.nio.ByteBuffer

object FrameCodec {
    private const val MAX_FRAME_SIZE = 64 * 1024

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_SIZE) {
            "Payload too large: ${payload.size} > $MAX_FRAME_SIZE"
        }
        return ByteBuffer.allocate(4 + payload.size).apply {
            putInt(payload.size)
            put(payload)
        }.array()
    }

    fun decode(buffer: ByteArray, offset: Int = 0): DecodeResult {
        // Нужно минимум 4 байта для чтения длины
        if (buffer.size - offset < 4) {
            return DecodeResult.Incomplete(4)
        }

        val length = ByteBuffer.wrap(buffer, offset, 4).int

        if (length < 0 || length > MAX_FRAME_SIZE) {
            return DecodeResult.Error("Invalid frame length: $length")
        }

        val totalNeeded = 4 + length
        if (buffer.size - offset < totalNeeded) {
            return DecodeResult.Incomplete(totalNeeded)
        }

        val payload = ByteArray(length)
        System.arraycopy(buffer, offset + 4, payload, 0, length)

        return DecodeResult.Success(payload, totalNeeded)
    }

    sealed class DecodeResult {
        data class Success(val data: ByteArray, val consumed: Int) : DecodeResult()
        data class Incomplete(val neededBytes: Int) : DecodeResult()
        data class Error(val message: String) : DecodeResult()
    }
}