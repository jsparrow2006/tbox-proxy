package dashingineering.jetour.tboxcore.util

fun fillHeader(dataLength: Int, tid: Byte, sid: Byte, param: Byte): ByteArray {
    val header = ByteArray(13)
    header[0] = 0x8E.toByte()
    header[1] = 0x5D
    header[2] = (dataLength + 10 shr 8).toByte()
    header[3] = (dataLength + 10 and 0xFF).toByte()
    header[4] = 0x00
    header[5] = 0x00
    header[6] = 0x01
    header[7] = 0x00
    header[8] = tid
    header[9] = sid
    header[10] = (dataLength shr 8).toByte()
    header[11] = (dataLength and 0xFF).toByte()
    header[12] = param
    return header
}

fun xorSum(data: ByteArray): Byte {
    if (data.size < 9) return 0
    var checksum: Byte = 0
    for (i in 9 until data.size) {
        checksum = (checksum.toInt() xor data[i].toInt()).toByte()
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