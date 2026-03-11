package dashingineering.jetour.tboxcore.udp

import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxClientCallback
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

class UdpSocketManager(
    private val localPort: Int,
    private val remotePort: Int,
    private val remoteAddress: InetAddress,
    private val callback: TBoxClientCallback
) {

    private var socket: DatagramSocket? = null
    private var scope: CoroutineScope? = null
    private var isReceiving = false

    private val log: (LogType, String, String) -> Unit = { type, tag, msg ->
        callback.onLogMessage(type, tag, msg)
    }

    fun initialize(): Boolean {
        return try {
            log(LogType.ERROR, "UdpSocketManager", "Try connect to port $localPort")
            socket = DatagramSocket(localPort).apply { soTimeout = 1000 }
            log(LogType.INFO, "UdpSocketManager", "Initialized on port $localPort")
            true
        } catch (e: SocketException) {
            if (e.message?.contains("EADDRINUSE") == true) {
                log(LogType.ERROR, "UdpSocketManager", "Address already in use on port $localPort, attempting to use different port")
                try {
                    socket = DatagramSocket(localPort).apply { soTimeout = 1000 }
                } catch (e: SocketException) {
                    log(LogType.ERROR, "UdpSocketManager", "Failed to bind port $localPort: ${e.message}")
                    false
                }
            } else {
                false
            }
        } as Boolean
    }

    fun startReceiving() {
        if (isReceiving) return
        isReceiving = true

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        callback.onLogMessage(LogType.INFO, "UdpSocketManager", "Starting receive loop on port $localPort")

        scope?.launch {
            val buffer = ByteArray(4096) // Буфер под ваши пакеты

            while (scope?.isActive == true && isReceiving) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

                    callback.onLogMessage(
                        LogType.DEBUG,
                        "UdpSocketManager",
                        "← UDP received ${data.size} bytes from ${packet.address}:${packet.port}: ${data.toLogString()}"
                    )

                    callback.onDataReceived(data)

                } catch (e: SocketTimeoutException) {
                    // Таймаут — это нормально, просто продолжаем цикл
                    continue
                } catch (e: Exception) {
                    if (isReceiving) {
                        log(LogType.ERROR, "UdpSocketManager", "Receive error: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun send(data: ByteArray): Boolean {
        val sock = socket ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val packet = DatagramPacket(data, data.size, remoteAddress, remotePort)
                sock.send(packet)

                callback.onLogMessage(
                    LogType.DEBUG,
                    "UdpSocketManager",
                    "→ UDP sent ${data.size} bytes to $remoteAddress:$remotePort: ${data.toLogString()}"
                )
                true
            } catch (e: Exception) {
                log(LogType.ERROR, "UdpSocketManager", "Send failed: ${e.message}")
                false
            }
        }
    }

    fun shutdown() {
        isReceiving = false
        scope?.cancel()
        socket?.close()
        socket = null
        log(LogType.INFO, "UdpSocketManager", "Shutdown complete")
    }

    fun isRunning(): Boolean = socket?.isClosed == false && isReceiving
}