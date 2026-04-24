package dashingineering.jetour.tboxcore.types

interface TBoxCallback {
    fun onDataReceived(data: ByteArray)
    fun onLogMessage(type: LogType, tag: String, message: String)
    fun onConnectionChanged(connected: Boolean) {

    }
}