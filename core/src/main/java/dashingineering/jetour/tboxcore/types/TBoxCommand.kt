package dashingineering.jetour.tboxcore.types

data class TBoxCommand (
    val tid: Byte,
    val sid: Byte,
    val cmd: Byte,
    val data: ByteArray,
    val textMessage: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TBoxCommand) return false
        return tid == other.tid && sid == other.sid && cmd == other.cmd &&
            data.contentEquals(other.data) && textMessage == other.textMessage
    }

    override fun hashCode(): Int {
        var result = tid.toInt()
        result = 31 * result + sid
        result = 31 * result + cmd
        result = 31 * result + data.contentHashCode()
        result = 31 * result + textMessage.hashCode()
        return result
    }
}
