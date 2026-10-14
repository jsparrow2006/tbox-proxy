package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.TBoxProxyInfo
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal object WireProtocol {
    private const val TBOX_MAGIC_0 = 0x8E.toByte()
    private const val TBOX_MAGIC_1 = 0x5D.toByte()
    private const val FLAG_NETWORK_BOUND = 0x01

    data class Hello(val protocolVersion: Int, val clientPackage: String, val libraryVersion: String)

    fun isTBoxPacket(body: ByteArray, offset: Int = 0): Boolean =
        body.size >= offset + 2 && body[offset] == TBOX_MAGIC_0 && body[offset + 1] == TBOX_MAGIC_1

    /**
     * Формат старого клиента по первому присланному им кадру.
     * Пакет TBox всегда начинается с `8E 5D`, у 2.1.x перед ним стоит `01`.
     * Всё нераспознанное считается 1.x — это формат по умолчанию для порта legacy.
     */
    fun detectLegacyClientFormat(body: ByteArray): TBoxWireProtocol =
        if (body.size >= 3 && body[0] == FrameCodec.TYPE_DATA && isTBoxPacket(body, 1)) {
            TBoxWireProtocol.TYPED_V2
        } else {
            TBoxWireProtocol.LEGACY_V1
        }

    /**
     * Формат старого хоста по первому кадру от него. `null` — кадр не позволяет определить
     * формат (тогда клиент ждёт следующего или применяет значение по умолчанию).
     */
    fun detectLegacyHostFormat(body: ByteArray): TBoxWireProtocol? = when {
        isTBoxPacket(body) -> TBoxWireProtocol.LEGACY_V1
        body.size >= 3 && body[0] == FrameCodec.TYPE_DATA && isTBoxPacket(body, 1) -> TBoxWireProtocol.TYPED_V2
        body.isNotEmpty() && body[0] == FrameCodec.TYPE_STATUS -> TBoxWireProtocol.TYPED_V2
        else -> null
    }

    fun encodeHello(clientPackage: String): ByteArray = write {
        writeByte(TBoxProxyInfo.PROTOCOL_VERSION)
        writeString(clientPackage)
        writeString(TBoxProxyInfo.LIBRARY_VERSION)
    }

    fun decodeHello(payload: ByteArray): Hello? = read(payload) {
        Hello(
            protocolVersion = readUnsignedByte(),
            clientPackage = readString(),
            libraryVersion = readString()
        )
    }

    fun encodeHostInfo(info: TBoxHostInfo): ByteArray = write {
        writeByte(TBoxProxyInfo.PROTOCOL_VERSION)
        writeString(info.hostPackage.orEmpty())
        writeString(info.libraryVersion.orEmpty())
        writeByte(if (info.networkBound) FLAG_NETWORK_BOUND else 0)
        writeString(info.networkInterface.orEmpty())
    }

    fun decodeHostInfo(payload: ByteArray): TBoxHostInfo? = read(payload) {
        readUnsignedByte()
        val hostPackage = readString()
        val libraryVersion = readString()
        val flags = readUnsignedByte()
        val networkInterface = readString()
        TBoxHostInfo(
            protocol = TBoxWireProtocol.V3,
            hostPackage = hostPackage.ifEmpty { null },
            libraryVersion = libraryVersion.ifEmpty { null },
            networkBound = flags and FLAG_NETWORK_BOUND != 0,
            networkInterface = networkInterface.ifEmpty { null }
        )
    }

    private inline fun write(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.block() }
        return bytes.toByteArray()
    }

    /** Лишние байты в конце игнорируются — так следующие версии могут добавлять поля. */
    private inline fun <T> read(payload: ByteArray, block: DataInputStream.() -> T): T? =
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { it.block() }
        } catch (_: Exception) {
            null
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val bytes = ByteArray(readUnsignedShort())
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
