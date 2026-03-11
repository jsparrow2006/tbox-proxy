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
        header[0] = 0x8E.toByte()      // Стартовый байт
        header[1] = 0x5D.toByte()      // Идентификатор протокола
        header[2] = (dataLength + 10 shr 8).toByte()  // Общая длина пакета (старший байт)
        header[3] = (dataLength + 10 and 0xFF).toByte() // Общая длина пакета (младший байт)
        header[4] = 0x00               // Sequence number
        header[5] = 0x00               // Reserved
        header[6] = 0x01               // Версия протокола
        header[7] = 0x00               // Reserved
        header[8] = tid                // ID целевого модуля
        header[9] = sid                // ID исходного модуля
        header[10] = (dataLength shr 8).toByte()  // Длина полезных данных (старший байт)
        header[11] = (dataLength and 0xFF).toByte() // Длина полезных данных (младший байт)
        header[12] = param             // Команда
        return header
    }

    /**
     * Конвертирует ByteArray в шестнадцатеричную строку
     * Пример: [0x8E, 0x5D, 0x01] → "8E5D01"
     */
    fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    /**
     * Конвертирует ByteArray в читаемый лог с обрезкой длинных сообщений
     * @param maxLength максимальное количество байт для вывода (0 = без обрезки)
     * @param prefix префикс перед каждой строкой (для многострочного вывода)
     */
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