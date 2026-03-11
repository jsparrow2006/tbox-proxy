package dashingineering.jetour.tboxcore.types

data class TBoxCommand (
    val tid: Byte,
    val sid: Byte,
    val cmd: Byte,
    val data: ByteArray
)