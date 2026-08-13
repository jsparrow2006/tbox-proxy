package dashingineering.jetour.tboxcore.types

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class TBoxStatus(
    val type: TBoxStatusType,
    val message: String,
    val details: String? = null
) {
    fun toByteArray(): ByteArray {
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        val detailsBytes = details?.toByteArray(StandardCharsets.UTF_8)

        val totalSize = 1 + 2 + messageBytes.size + 2 + (detailsBytes?.size ?: 0)
        return ByteBuffer.allocate(totalSize).apply {
            put(type.ordinal.toByte())
            putShort(messageBytes.size.toShort())
            put(messageBytes)
            putShort((detailsBytes?.size ?: 0).toShort())
            detailsBytes?.let { put(it) }
        }.array()
    }

    companion object {
        fun fromByteArray(data: ByteArray): TBoxStatus? {
            if (data.size < 3) return null
            return try {
                val buffer = ByteBuffer.wrap(data)
                val typeOrdinal = buffer.get().toInt()
                val type = TBoxStatusType.entries.getOrNull(typeOrdinal) ?: return null

                val messageLen = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() < messageLen) return null
                val messageBytes = ByteArray(messageLen)
                buffer.get(messageBytes)
                val message = String(messageBytes, StandardCharsets.UTF_8)

                val detailsLen = buffer.short.toInt() and 0xFFFF
                val details = if (detailsLen > 0 && buffer.remaining() >= detailsLen) {
                    val detailsBytes = ByteArray(detailsLen)
                    buffer.get(detailsBytes)
                    String(detailsBytes, StandardCharsets.UTF_8)
                } else null

                TBoxStatus(type, message, details)
            } catch (e: Exception) {
                null
            }
        }
    }
}
