package dashingineering.jetour.tboxcore.types

import dashingineering.jetour.tboxcore.util.TBoxReceivedMessage

interface TBoxClientCallback {
    fun onDataReceived(message: TBoxReceivedMessage)
    fun onLogMessage(type: LogType, tag: String, message: String)
    fun onConnectionChanged(connected: Boolean) {}
    fun onStatusChanged(status: TBoxStatus) {}
}