// tcp/TcpServer.kt
package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClientCallback
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
import dashingineering.jetour.tboxcore.util.toHex
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class TcpServer(
    private val port: Int,
    private val udpManager: UdpSocketManager,
    private val callback: TBoxClientCallback
) {

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientHandler>()
    private var scope: CoroutineScope? = null
    private var isRunning = false

    private val log: (LogType, String, String) -> Unit = { type, tag, msg ->
        callback.onLogMessage(type, tag, msg)
    }

    suspend fun start(): Boolean {
        if (isRunning) return true

        return try {
            // 🔧 Явно биндим на 127.0.0.1 для стабильности в эмуляторе
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
            }

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            isRunning = true

            log(LogType.INFO, "TcpServer", "Started on port $port")

            // 🔧 Запускаем цикл accept() в ОТДЕЛЬНОЙ корутине
            scope?.launch {
                acceptLoop()
            }

            // 🔧 Теперь эта строка выполнится сразу!
            log(LogType.INFO, "TcpServer", "Accept loop started, ready for clients")
            true

        } catch (e: Exception) {
            log(LogType.ERROR, "TcpServer", "Start failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // 🔧 НОВЫЙ метод: цикл приёма подключений (не блокирует start())
    private suspend fun acceptLoop() {
        while (isRunning && !serverSocket?.isClosed!!) {
            try {
                // accept() блокирует, но это ок — мы в отдельной корутине на IO
                val clientSocket = serverSocket?.accept() ?: break
                scope?.launch {
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log(LogType.ERROR, "TcpServer", "Accept error: ${e.message}")
                }
            }
        }
        log(LogType.INFO, "TcpServer", "Accept loop stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        val handler = ClientHandler(socket, callback)
        clients.add(handler)

        log(LogType.INFO, "TcpServer", "Client connected (${clients.size} total)")

        try {
            while (isRunning && handler.isActive()) {
                val data = handler.receive() ?: break

                // TCP → UDP: пересылка в сокет
                val sent = udpManager.send(data)
                log(LogType.DEBUG, "TcpServer", "Forwarded ${data.size} bytes to UDP: ${sent}")
            }
        } catch (e: Exception) {
            log(LogType.ERROR, "TcpServer", "Client handler error: ${e.message}")
        } finally {
            clients.remove(handler)
            handler.close()
            log(LogType.INFO, "TcpServer", "Client disconnected (${clients.size} remaining)")
        }
    }

    // Рассылка UDP → TCP
    fun broadcastToClients( data: ByteArray) {
        if (clients.isEmpty()) return
        val frame = FrameCodec.encode(data)
        scope?.launch {
            clients.forEach { client ->
                try {
                    client.sendRaw(frame)
                } catch (e: Exception) {
                    log(LogType.WARN, "TcpServer", "Send to client failed: ${e.message}")
                }
            }
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        clients.forEach { it.close() }
        clients.clear()
        serverSocket?.close()
        serverSocket = null
        scope?.cancel()
        scope = null

        log(LogType.INFO, "TcpServer", "Stopped")
    }

    fun getConnectedClientsCount(): Int = clients.size
}

// ... ClientHandler класс без изменений ...
private class ClientHandler(
    private val socket: Socket,
    private val callback: TBoxClientCallback
) {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val buffer = ByteArray(65536)
    private var offset = 0

    fun isActive(): Boolean = socket.isConnected && !socket.isClosed

    suspend fun receive(): ByteArray? {
        return try {
            if (input.available() > 0) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) return null
                offset += read

                var processed = 0
                while (processed < offset) {
                    when (val result = FrameCodec.decode(buffer, processed)) {
                        is FrameCodec.DecodeResult.Success -> {
                            return result.data.also {
                                if (result.consumed < offset) {
                                    val remaining = offset - result.consumed
                                    System.arraycopy(buffer, result.consumed, buffer, 0, remaining)
                                    offset = remaining
                                } else {
                                    offset = 0
                                }
                            }
                        }
                        is FrameCodec.DecodeResult.Incomplete -> return null
                        is FrameCodec.DecodeResult.Error -> {
                            callback.onLogMessage(LogType.ERROR, "ClientHandler", result.message)
                            return null
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun sendRaw(frame: ByteArray): Boolean {
        return try {
            output.write(frame)
            output.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }
}