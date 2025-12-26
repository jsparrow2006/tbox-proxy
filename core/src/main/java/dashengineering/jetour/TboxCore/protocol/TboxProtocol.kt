package dashengineering.jetour.TboxCore.protocol
import kotlin.experimental.xor

// Константы модулей
const val APP_CODE: Byte = 0x2F
const val MDC_CODE: Byte = 0x25
const val LOC_CODE: Byte = 0x29
const val CRT_CODE: Byte = 0x23
const val SWD_CODE: Byte = 0x2D
const val NTM_CODE: Byte = 0x24
const val HUM_CODE: Byte = 0x30
const val UDA_CODE: Byte = 0x38
const val SELF_CODE: Byte = 0x50

// Утилиты
fun fillHeader(dataLength: Int, tid: Byte, sid: Byte, param: Byte): ByteArray {
    val header = ByteArray(13)
    header[0] = 0x8E.toByte() // Стартовый байт
    header[1] = 0x5D // Идентификатор протокола
    header[2] = (dataLength + 10 shr 8).toByte()
    header[3] = (dataLength + 10 and 0xFF).toByte()
    header[4] = 0x00 // Sequence
    header[5] = 0x00 // Reserved
    header[6] = 0x01 // Версия
    header[7] = 0x00 // Reserved
    header[8] = tid  // TID
    header[9] = sid  // SID
    header[10] = (dataLength shr 8).toByte()
    header[11] = (dataLength and 0xFF).toByte()
    header[12] = param
    return header
}

fun xorSum(data: ByteArray): Byte {
    if (data.isEmpty() || data.size < 9) return 0
    var checksum: Byte = 0
    for (i in 9 until data.size) {
        checksum = checksum xor data[i]
    }
    return checksum
}

fun checkPacket(data: ByteArray): Boolean {
    return data.size >= 14 && data[0] == 0x8E.toByte() && data[1] == 0x5D.toByte()
}

fun extractDataLength(data: ByteArray): Int {
    return ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
}

fun checkLength(data: ByteArray, length: Int): Boolean {
    return data.size - 14 >= length
}

fun extractData(data: ByteArray, length: Int): ByteArray {
    val payload = data.copyOfRange(13, 13 + length)
    val checksum = xorSum(data.copyOfRange(0, 13 + length))
    return if (checksum == data[13 + length]) payload else ByteArray(0)
}

fun toHexString(data: ByteArray, separator: String = " "): String {
    return data.joinToString(separator) { "%02X".format(it) }
}

// Расширения для байтов
fun Byte.toUInt(): UInt = this.toUByte().toUInt()

fun Byte.extractBitsToUInt(startPos: Int, length: Int): UInt {
    require(startPos in 0..7) { "startPos must be 0-7" }
    require(length in 1..8) { "length must be 1-8" }
    require(startPos + length <= 8) { "startPos+length ≤ 8" }
    val value = this.toUInt() and 0xFFu
    val mask = (1u shl length) - 1u
    return (value shr startPos) and mask
}

fun Byte.getLeftNibble(): Int = (this.toInt() shr 4) and 0x0F
fun Byte.getRightNibble(): Int = this.toInt() and 0x0F

fun ByteArray.toUInt20FromNibbleBigEndian(): UInt {
    require(size >= 3)
    val b1 = (this[0].toUInt() and 0x0Fu) shl 16
    val b2 = (this[1].toUInt() and 0xFFu) shl 8
    val b3 = this[2].toUInt() and 0xFFu
    return b1 or b2 or b3
}

fun ByteArray.toUInt16BigEndian(): UInt {
    require(size >= 2)
    return ((this[0].toUInt() and 0xFFu) shl 8) or (this[1].toUInt() and 0xFFu)
}

fun ByteArray.toFloat(format: String = "UINT16_BE"): Float {
    return when (format) {
        "UINT16_BE" -> {
            require(size >= 2)
            (((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)).toFloat()
        }
        "UINT16_LE" -> {
            require(size >= 2)
            (((this[1].toInt() and 0xFF) shl 8) or (this[0].toInt() and 0xFF)).toFloat()
        }
        "UINT24_BE" -> {
            require(size >= 3)
            (((this[0].toInt() and 0xFF) shl 16) or
                    ((this[1].toInt() and 0xFF) shl 8) or
                    (this[2].toInt() and 0xFF)).toFloat()
        }
        "UINT24_LE" -> {
            require(size >= 3)
            (((this[2].toInt() and 0xFF) shl 16) or
                    ((this[1].toInt() and 0xFF) shl 8) or
                    (this[0].toInt() and 0xFF)).toFloat()
        }
        else -> throw IllegalArgumentException("Unsupported format: $format")
    }
}

fun ByteArray.decodeToString(start: Int = 0, end: Int = size): String {
    return try {
        String(this, start, end - start, Charsets.UTF_8).trimEnd()
    } catch (e: Exception) {
        "-"
    }
}