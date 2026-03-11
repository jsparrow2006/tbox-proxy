package dashingineering.jetour.tboxcore.util

object ByteConverter {

    fun toHexString(data: ByteArray, separator: String = " "): String {
        return data.joinToString(separator) { "%02X".format(it) }
    }

    fun ByteArray.toUInt20FromNibbleBigEndian(): Int {
        require(this.size >= 3) { "ByteArray must have at least 3 bytes" }
        return ((this[0].toInt() and 0x0F) shl 16) or
                ((this[1].toInt() and 0xFF) shl 8) or
                (this[2].toInt() and 0xFF)
    }

    fun ByteArray.toDouble(format: String = "UINT16_BE"): Double {
        return when (format) {
            "UINT16_BE" -> {
                require(this.size >= 2) { "ByteArray must have at least 2 bytes for UINT16_BE" }
                val intValue = ((this[0].toInt() and 0xFF) shl 8) or
                        (this[1].toInt() and 0xFF)
                intValue.toDouble()
            }
            "UINT16_LE" -> {
                require(this.size >= 2) { "ByteArray must have at least 2 bytes for UINT16_LE" }
                val intValue = ((this[1].toInt() and 0xFF) shl 8) or
                        (this[0].toInt() and 0xFF)
                intValue.toDouble()
            }
            "UINT24_BE" -> {
                require(this.size >= 3) { "ByteArray must have at least 3 bytes for UINT24_BE" }
                val intValue = ((this[0].toInt() and 0xFF) shl 16) or
                        ((this[1].toInt() and 0xFF) shl 8) or
                        (this[2].toInt() and 0xFF)
                intValue.toDouble()
            }
            "UINT24_LE" -> {
                require(this.size >= 3) { "ByteArray must have at least 3 bytes for UINT24_LE" }
                val intValue = ((this[2].toInt() and 0xFF) shl 16) or
                        ((this[1].toInt() and 0xFF) shl 8) or
                        (this[0].toInt() and 0xFF)
                intValue.toDouble()
            }
            else -> throw IllegalArgumentException("Unknown format: $format")
        }
    }

    fun ByteArray.toFloat(format: String = "UINT16_BE"): Float {
        return when (format) {
            "UINT16_BE" -> {
                require(this.size >= 2) { "ByteArray must have at least 2 bytes for UINT16_BE" }
                val intValue = ((this[0].toInt() and 0xFF) shl 8) or
                        (this[1].toInt() and 0xFF)
                intValue.toFloat()
            }
            "UINT16_LE" -> {
                require(this.size >= 2) { "ByteArray must have at least 2 bytes for UINT16_LE" }
                val intValue = ((this[1].toInt() and 0xFF) shl 8) or
                        (this[0].toInt() and 0xFF)
                intValue.toFloat()
            }
            "UINT24_BE" -> {
                require(this.size >= 3) { "ByteArray must have at least 3 bytes for UINT24_BE" }
                val intValue = ((this[0].toInt() and 0xFF) shl 16) or
                        ((this[1].toInt() and 0xFF) shl 8) or
                        (this[2].toInt() and 0xFF)
                intValue.toFloat()
            }
            "UINT24_LE" -> {
                require(this.size >= 3) { "ByteArray must have at least 3 bytes for UINT24_LE" }
                val intValue = ((this[2].toInt() and 0xFF) shl 16) or
                        ((this[1].toInt() and 0xFF) shl 8) or
                        (this[0].toInt() and 0xFF)
                intValue.toFloat()
            }
            else -> throw IllegalArgumentException("Unknown format: $format")
        }
    }

    fun xorSum(data: ByteArray): Byte {
        if (data.isEmpty() || data.size < 9) return 0
        var checksum: Byte = 0
        for (i in 9 until data.size) {
            checksum = (checksum.toInt() xor data[i].toInt()).toByte()
        }
        return checksum
    }

    fun fillHeader(dataLength: Int, tid: Byte, sid: Byte, param: Byte): ByteArray {
        val header = ByteArray(13)
        header[0] = 0x8E.toByte()
        header[1] = 0x5D.toByte()
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

    fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    fun ByteArray.toLogString(maxLength: Int = 64, prefix: String = ""): String {
        return when {
            isEmpty() -> "${prefix}<empty>"
            maxLength > 0 && size > maxLength -> {
                val hex = toHex().take(maxLength * 2)
                "${prefix}${hex}... (${size} bytes total)"
            }
            else -> "${prefix}${toHex()} (${size} bytes)"
        }
    }
}