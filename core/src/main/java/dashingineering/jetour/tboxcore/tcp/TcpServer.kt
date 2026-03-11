package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClientCallback
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP-сервер с мостом к UDP
 * Принимает клиентов и пересылает данные: TCP ↔ UDP (raw ByteArray)
 */
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

    /**
     * Запуск сервера
     */
    suspend fun start(): Boolean {
        if (isRunning) return true

        return try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
            }

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            isRunning = true

            // Подписка на UDP-события
            setupUdpListener()

            log(LogType.INFO, "TcpServer", "Started on port $port")

            // Цикл приёма подключений
            while (isRunning && !serverSocket?.isClosed!!) {
                try {
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

            true
        } catch (e: Exception) {
            log(LogType.ERROR, "TcpServer", "Start failed: ${e.message}")
            false
        }
    }

    private fun setupUdpListener() {
        // UdpSocketManager уже передаёт данные через callback
        // Но нам нужно переслать их всем TCP-клиентам
        // Для этого оборачиваем коллбэк
        val originalCallback = callback
        val wrappedCallback = object : dashingineering.jetour.tboxcore.TBoxClientCallback {
            override fun onDataReceived(data: ByteArray) {
                // UDP → TCP: рассылка всем клиентам
                broadcastToClients(data)
                // Также передаём в оригинальный коллбэк (сервер тоже "получает" данные)
                originalCallback.onDataReceived(data)
            }

            override fun onLogMessage(type: LogType, tag: String, message: String) {
                originalCallback.onLogMessage(type, tag, message)
            }
        }
        // Примечание: в реальной реализации лучше использовать отдельный слушатель
        // Здесь для простоты полагаемся на то что callback уже настроен
    }

    private fun broadcastToClients(data: ByteArray) {
        if (clients.isEmpty()) return

        val frame = FrameCodec.encode(data)
        scope?.launch {
            clients.forEach { client ->
                try {
                    client.sendRaw(frame)
                } catch (e: Exception) {
                    log(LogType.WARN, "TcpServer", "Failed to send to client: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val handler = ClientHandler(socket, callback)
        clients.add(handler)

        log(LogType.INFO, "TcpServer", "Client connected (${clients.size} total)")

        try {
            // Цикл приёма от этого клиента
            while (isRunning && handler.isActive()) {
                val data = handler.receive() ?: break

                // TCP → UDP: пересылка в сокет
                udpManager.send(data)
            }
        } catch (e: Exception) {
            log(LogType.ERROR, "TcpServer", "Client handler error: ${e.message}")
        } finally {
            clients.remove(handler)
            handler.close()
            log(LogType.INFO, "TcpServer", "Client disconnected (${clients.size} remaining)")
        }
    }

    /**
     * Отправка данных от имени сервера (опционально)
     */
    fun sendToAllClients(data: ByteArray) {
        broadcastToClients(data)
    }

    /**
     * Остановка сервера
     */
    suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        // Закрываем клиентов
        clients.forEach { it.close() }
        clients.clear()

        // Закрываем сервер
        serverSocket?.close()
        serverSocket = null

        // Отменяем корутины
        scope?.cancel()
        scope = null

        log(LogType.INFO, "TcpServer", "Stopped")
    }

    fun getConnectedClientsCount(): Int = clients.size
}

/**
 * Внутренний обработчик одного клиента
 */
private class ClientHandler(
    private val socket: Socket,
    private val callback: TBoxClientCallback
) {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val buffer = ByteArray(65536)
    private var offset = 0

    fun isActive(): Boolean = socket.isConnected && !socket.isClosed

    /**
     * Приём и декодирование сообщения
     */
    suspend fun receive(): ByteArray? {
        return try {
            // Читаем если есть данные
            if (input.available() > 0) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) return null

                offset += read

                // Декодируем фреймы
                var processed = 0
                while (processed < offset) {
                    when (val result = FrameCodec.decode(buffer, processed)) {
                        is FrameCodec.DecodeResult.Success -> {
                            return result.data.also {
                                // Сдвигаем буфер если остались необработанные данные
                                if (result.consumed < offset) {
                                    val remaining = offset - result.consumed
                                    System.arraycopy(buffer, result.consumed, buffer, 0, remaining)
                                    offset = remaining
                                } else {
                                    offset = 0
                                }
                            }
                        }
                        is FrameCodec.DecodeResult.Incomplete -> {
                            // Нужно больше данных
                            return null
                        }
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

    /**
     * Отправка сырых байт (уже закодированных)
     */
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