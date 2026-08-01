package dashingineering.jetour.tboxcore.util

import dashingineering.jetour.tboxcore.util.ByteConverter.extractData
import dashingineering.jetour.tboxcore.util.ByteConverter.extractDataLength

class TBoxReceivedMessage(rawMessage: ByteArray) {
    private val _raw = rawMessage.copyOf()

    // Вычисляем валидность без выброса исключений
    val isValid: Boolean = run {
        if (_raw.size < 14) return@run false
        if (_raw[0] != 0x8E.toByte() || _raw[1] != 0x5D.toByte()) return@run false

        val dataLength = extractDataLength(_raw)
        if (_raw.size - 14 < dataLength) return@run false

        val data = extractData(_raw, dataLength)
        data.isNotEmpty()
    }

    val tid: Byte = _raw.getOrElse(9) { 0 }
    val sid: Byte = _raw.getOrElse(8) { 0 }
    val command: Byte = _raw.getOrElse(12) { 0 }
    val payload: ByteArray = if (isValid) extractData(_raw, extractDataLength(_raw)) else ByteArray(0)

    fun getRawData(): ByteArray = _raw.copyOf()
}