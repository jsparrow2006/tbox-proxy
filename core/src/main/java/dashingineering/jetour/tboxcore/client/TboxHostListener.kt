package dashingineering.jetour.tboxcore.client

interface TboxHostListener {
    suspend fun onDataReceived( data: ByteArray)
    suspend fun onLog(level: String, tag: String, message: String)
    suspend fun onHostConnected()
    suspend fun onHostDisconnected()
}