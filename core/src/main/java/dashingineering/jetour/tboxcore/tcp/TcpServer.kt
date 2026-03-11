package dashingineering.jetour.tboxcore.tcp

import android.os.Handler
import android.os.Looper
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxClientCallback
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientHandler>()
    private var scope: CoroutineScope? = null
    private var isRunning = false

    private fun onLogMessage(type: LogType, tag: String, message: String) {
        mainHandler.post {
            callback.onLogMessage(type, tag, message)
        }
    }

    suspend fun start(): Boolean {
        if (isRunning) return true

        return withContext(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                }

                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                isRunning = true

                onLogMessage(LogType.INFO, "TcpServer", "Started on port $port")

                scope?.launch {
                    acceptLoop()
                }

                onLogMessage(LogType.INFO, "TcpServer", "Accept loop started, ready for clients")
                true

            } catch (e: Exception) {
                onLogMessage(LogType.ERROR, "TcpServer", "Start failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    private suspend fun acceptLoop() {
        while (isRunning && !serverSocket?.isClosed!!) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                onLogMessage(LogType.INFO, "TcpServer", "Client connected from ${clientSocket.inetAddress}")

                val handler = ClientHandler(clientSocket, callback, udpManager)
                clients.add(handler)
                onLogMessage(LogType.INFO, "TcpServer", "Total clients: ${clients.size}")

                scope?.launch {
                    handler.handle()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onLogMessage(LogType.ERROR, "TcpServer", "Accept error: ${e.message}")
                }
            }
        }
        onLogMessage(LogType.INFO, "TcpServer", "Accept loop stopped")
    }

    fun broadcastToClients( data: ByteArray) {
        if (clients.isEmpty()) {
            onLogMessage(LogType.DEBUG, "TcpServer", "No clients to broadcast to")
            return
        }

        val frame = FrameCodec.encode(data)
        scope?.launch {
            var sentCount = 0
            clients.forEach { client ->
                try {
                    client.sendRaw(frame)
                    sentCount++
                } catch (e: Exception) {
                    onLogMessage(LogType.WARN, "TcpServer", "Send to client failed: ${e.message}")
                }
            }
            onLogMessage(LogType.DEBUG, "TcpServer", "Broadcast to $sentCount/${clients.size} clients: ${data.size} bytes")
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        onLogMessage(LogType.INFO, "TcpServer", "Stopping...")

        // Закрываем всех клиентов
        clients.forEach { it.close() }
        clients.clear()

        // Закрываем сервер
        serverSocket?.close()
        serverSocket = null

        // Отменяем корутины
        scope?.cancel()
        scope = null

        onLogMessage(LogType.INFO, "TcpServer", "Stopped")
    }

    fun getConnectedClientsCount(): Int = clients.size
}

private class ClientHandler(
    private val socket: Socket,
    private val callback: TBoxClientCallback,
    private val udpManager: UdpSocketManager
) {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val buffer = ByteArray(65536)
    private var offset = 0
    private var isActive = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onLogMessage(type: LogType, tag: String, message: String) {
        mainHandler.post {
            callback.onLogMessage(type, tag, message)
        }
    }

    suspend fun handle() {
        try {
            while (isActive && socket.isConnected && !socket.isClosed) {
                val data = receive()
                if (data == null) {
                    // Нет данных или ошибка — небольшая пауза
                    delay(10)
                    continue
                }

                onLogMessage(LogType.DEBUG, "ClientHandler", "Received ${data.size} bytes from client, forwarding to UDP")
                val sent = udpManager.send(data)
                onLogMessage(LogType.DEBUG, "ClientHandler", "Forwarded to UDP: $sent")
            }
        } catch (e: Exception) {
            onLogMessage(LogType.ERROR, "ClientHandler", "Handler error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            close()
        }
    }

    suspend fun receive(): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val available = input.available()
                if (available <= 0) {
                    return@withContext null
                }

                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) {
                    return@withContext null
                }

                offset += read

                var processed = 0
                while (processed < offset) {
                    when (val result = FrameCodec.decode(buffer, processed)) {
                        is FrameCodec.DecodeResult.Success -> {
                            // Сдвигаем буфер если остались необработанные данные
                            if (result.consumed < offset) {
                                val remaining = offset - result.consumed
                                System.arraycopy(buffer, result.consumed, buffer, 0, remaining)
                                offset = remaining
                            } else {
                                offset = 0
                            }
                            return@withContext result.data
                        }
                        is FrameCodec.DecodeResult.Incomplete -> {
                            // Нужно больше данных
                            return@withContext null
                        }
                        is FrameCodec.DecodeResult.Error -> {
                            onLogMessage(LogType.ERROR, "ClientHandler", result.message)
                            this@ClientHandler.isActive = false
                            return@withContext null
                        }
                    }
                }
                null
            } catch (e: Exception) {
                onLogMessage(LogType.ERROR, "ClientHandler", "Receive error: ${e.message}")
                this@ClientHandler.isActive = false
                null
            }
        }
    }

    suspend fun sendRaw(frame: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                output.write(frame)
                output.flush()
                true
            } catch (e: Exception) {
                onLogMessage(LogType.ERROR, "ClientHandler", "Send failed: ${e.message}")
                false
            }
        }
    }

    fun close() {
        if (!isActive) return
        isActive = false

        try { socket.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}

        onLogMessage(LogType.INFO, "ClientHandler", "Client connection closed")
    }
}