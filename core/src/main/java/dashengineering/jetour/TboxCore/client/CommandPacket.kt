package dashengineering.jetour.TboxCore.client

data class CommandPacket(
    val tid: Byte,
    val sid: Byte,
    val cmd: Byte,
    val data: ByteArray
)