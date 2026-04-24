package dashingineering.jetour.tboxcore.util

import dashingineering.jetour.tboxcore.util.ByteConverter.extractData
import dashingineering.jetour.tboxcore.util.ByteConverter.extractDataLength

class TBoxReceivedMessage(private val rawMessage: ByteArray) {
    private val _raw: ByteArray = rawMessage.copyOf()

    init {
        require(_raw.size >= 14) { "Packet too short: ${_raw.size}" }
        require(_raw[0] == 0x8E.toByte() && _raw[1] == 0x5D.toByte()) { "Invalid magic bytes" }
    }
    private val dataLength = extractDataLength(_raw)

    val tid = _raw[9]
    val sid = _raw[8]
    val command = _raw[12]
    val payload = extractData(_raw, dataLength)

    val isValid: Boolean get() = payload.isNotEmpty()

    fun getRawData(): ByteArray {
        return _raw
    }
}