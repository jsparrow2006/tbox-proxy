package dashingineering.jetour.tboxcore.tcp

import java.nio.ByteBuffer

/**
 * Собирает тела кадров (всё после 4-байтной длины) из TCP-потока.
 * Не зависит от версии протокола: интерпретацию тела делает вызывающий код.
 * Возвращает все кадры, пришедшие одним `read()`, и корректно ждёт хвост неполного кадра.
 */
internal class FrameReader(initialCapacity: Int = 8 * 1024) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    class FrameException(message: String) : Exception(message)

    /** Добавляет прочитанные байты и возвращает все полностью собранные тела кадров. */
    @Throws(FrameException::class)
    fun append(chunk: ByteArray, length: Int = chunk.size): List<ByteArray> {
        ensureCapacity(size + length)
        System.arraycopy(chunk, 0, buffer, size, length)
        size += length

        val bodies = ArrayList<ByteArray>(1)
        var offset = 0
        while (size - offset >= 4) {
            val bodyLength = ByteBuffer.wrap(buffer, offset, 4).int
            if (bodyLength < 1 || bodyLength > FrameCodec.MAX_FRAME_SIZE) {
                throw FrameException("Invalid frame length: $bodyLength")
            }
            if (size - offset < 4 + bodyLength) break
            bodies += buffer.copyOfRange(offset + 4, offset + 4 + bodyLength)
            offset += 4 + bodyLength
        }

        if (offset > 0) {
            System.arraycopy(buffer, offset, buffer, 0, size - offset)
            size -= offset
        }
        return bodies
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newSize = buffer.size
        while (newSize < required) newSize *= 2
        buffer = buffer.copyOf(newSize)
    }
}
