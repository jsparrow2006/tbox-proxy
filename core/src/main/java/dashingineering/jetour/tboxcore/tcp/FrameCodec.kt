package dashingineering.jetour.tboxcore.tcp

import java.nio.ByteBuffer

/**
 * Простое кодирование сообщений с префиксом длины
 * Формат: [length:4 bytes][payload:N bytes]
 *
 * Это нужно чтобы разделять потоки байт в TCP на отдельные сообщения
 */
object FrameCodec {

    private const val MAX_FRAME_SIZE = 64 * 1024 // 64KB лимит

    /**
     * Кодирование: добавляет 4-байтный префикс длины
     */
    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_SIZE) {
            "Payload too large: ${payload.size} > $MAX_FRAME_SIZE"
        }
        return ByteBuffer.allocate(4 + payload.size).apply {
            putInt(payload.size)
            put(payload)
        }.array()
    }

    /**
     * Декодирование: читает длину и извлекает фрейм
     * @return декодированные данные или null если данных недостаточно
     */
    fun decode(buffer: ByteArray, offset: Int = 0): DecodeResult {
        // Нужно минимум 4 байта для чтения длины
        if (buffer.size - offset < 4) {
            return DecodeResult.Incomplete(4)
        }

        val length = ByteBuffer.wrap(buffer, offset, 4).int

        // Валидация размера
        if (length < 0 || length > MAX_FRAME_SIZE) {
            return DecodeResult.Error("Invalid frame length: $length")
        }

        // Проверяем, есть ли весь фрейм в буфере
        val totalNeeded = 4 + length
        if (buffer.size - offset < totalNeeded) {
            return DecodeResult.Incomplete(totalNeeded)
        }

        // Извлекаем полезные данные
        val payload = ByteArray(length)
        System.arraycopy(buffer, offset + 4, payload, 0, length)

        return DecodeResult.Success(payload, totalNeeded)
    }

    /**
     * Результат декодирования
     */
    sealed class DecodeResult {
        data class Success(val data: ByteArray, val consumed: Int) : DecodeResult()
        data class Incomplete(val neededBytes: Int) : DecodeResult()
        data class Error(val message: String) : DecodeResult()
    }
}