package dashingineering.jetour.tboxcore.tcp

import java.nio.ByteBuffer

/**
 * Кадры TCP-моста. Все версии используют один и тот же префикс длины (4 байта, big-endian),
 * различается только содержимое тела:
 *
 * - legacy v1 (библиотека 1.0.x–2.0.x): `[len][payload]`;
 * - typed (2.1.x и порт v3): `[len][type][payload]`, `len = 1 + payload.size`.
 *
 * Подробности — `PROTOCOL.md`.
 */
object FrameCodec {
    const val TYPE_DATA: Byte = 0x01
    const val TYPE_STATUS: Byte = 0x02
    const val TYPE_HELLO: Byte = 0x10
    const val TYPE_HOST_INFO: Byte = 0x11

    const val MAX_FRAME_SIZE = 64 * 1024

    /** Typed-кадр (2.1.x и v3). */
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

    /** Кадр без байта типа (1.0.x–2.0.x). */
    fun encodeLegacy(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_FRAME_SIZE) {
            "Invalid legacy payload size: ${payload.size}"
        }
        return ByteBuffer.allocate(4 + payload.size).apply {
            putInt(payload.size)
            put(payload)
        }.array()
    }

    /**
     * Разбор typed-кадра из `buffer[offset, limit)`.
     * `limit` — граница реально прочитанных данных, а не размер буфера.
     */
    fun decode(buffer: ByteArray, offset: Int = 0, limit: Int = buffer.size): DecodeResult {
        val available = limit - offset
        if (available < 4) {
            return DecodeResult.Incomplete(4)
        }

        val length = ByteBuffer.wrap(buffer, offset, 4).int
        if (length < 1 || length > MAX_FRAME_SIZE) {
            return DecodeResult.Error("Invalid frame length: $length")
        }

        val totalNeeded = 4 + length
        if (available < totalNeeded) {
            return DecodeResult.Incomplete(totalNeeded)
        }

        val type = buffer[offset + 4]
        val payload = buffer.copyOfRange(offset + 5, offset + totalNeeded)
        return DecodeResult.Success(payload, totalNeeded, type)
    }

    sealed class DecodeResult {
        data class Success(val data: ByteArray, val consumed: Int, val type: Byte = TYPE_DATA) : DecodeResult()
        data class Incomplete(val neededBytes: Int) : DecodeResult()
        data class Error(val message: String) : DecodeResult()
    }
}
