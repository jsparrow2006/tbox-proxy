package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClientCallback
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket


class TcpClient(
    private val host: String,
    private val port: Int,
    private val callback: TBoxClientCallback
) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var scope: CoroutineScope? = null
    private val sendMutex = Mutex()

    var isConnected: Boolean = false
        private set

    private val log: (LogType, String, String) -> Unit = { type, tag, msg ->
        callback.onLogMessage(type, tag, msg)
    }

    suspend fun connect(): Boolean {
        if (isConnected) return true

        return try {
            log(LogType.INFO, "TcpClient", "Connecting to $host:$port")

            socket = Socket()
            socket?.connect(InetSocketAddress(host, port), 3000)
            socket?.soTimeout = 0 // blocking mode

            input = DataInputStream(socket?.getInputStream())
            output = DataOutputStream(socket?.getOutputStream())

            isConnected = true
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            // Запускаем приём
            scope?.launch { receiveLoop() }

            log(LogType.INFO, "TcpClient", "Connected successfully")
            callback.onConnectionChanged(true)
            true

        } catch (e: Exception) {
            log(LogType.ERROR, "TcpClient", "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
            log(LogType.ERROR, "TcpClient", "Stack: ${e.stackTrace.take(3).joinToString(" → ")}")
            cleanup()
            false
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65536) // 64KB буфер
        var offset = 0

        while (isConnected && socket?.isConnected == true) {
            try {
                // Читаем доступные данные
                val available = input?.available() ?: 0
                if (available > 0) {
                    val read = input?.read(buffer, offset, buffer.size - offset) ?: -1
                    if (read > 0) {
                        offset += read

                        // Пытаемся декодировать фреймы
                        var processed = 0
                        while (processed < offset) {
                            when (val result = FrameCodec.decode(buffer, processed)) {
                                is FrameCodec.DecodeResult.Success -> {
                                    // Есть полное сообщение — передаём в коллбэк
                                    log(LogType.INFO, "TcpClient", "Received Raw Data: ${result.data.toLogString()}")
                                    callback.onDataReceived(result.data)
                                    processed += result.consumed
                                }
                                is FrameCodec.DecodeResult.Incomplete -> {
                                    // Нужно больше данных
                                    break
                                }
                                is FrameCodec.DecodeResult.Error -> {
                                    log(LogType.ERROR, "TcpClient", "Decode error: ${result.message}")
                                    disconnect()
                                    return
                                }
                            }
                        }

                        // Сдвигаем необработанные данные в начало буфера
                        if (processed > 0 && processed < offset) {
                            val remaining = offset - processed
                            System.arraycopy(buffer, processed, buffer, 0, remaining)
                            offset = remaining
                        } else if (processed >= offset) {
                            offset = 0
                        }
                    } else if (read == -1) {
                        // Конец потока
                        disconnect("Server closed connection")
                        return
                    }
                } else {
                    // Нет данных — небольшая пауза
                    delay(10)
                }
            } catch (e: Exception) {
                if (isConnected) {
                    log(LogType.ERROR, "TcpClient", "Receive error: ${e.message}")
                    disconnect("Receive error")
                }
                return
            }
        }
    }

    /**
     * Отправка сырых данных
     */
    suspend fun send(data: ByteArray): Boolean {
        if (!isConnected || output == null) {
            log(LogType.WARN, "TcpClient", "Cannot send: not connected")
            return false
        }

        return sendMutex.withLock {
            try {
                val frame = FrameCodec.encode(data)
                output?.write(frame)
                output?.flush()
                true
            } catch (e: Exception) {
                log(LogType.ERROR, "TcpClient", "Send failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Отключение
     */
    fun disconnect(reason: String = "Manual") {
        if (!isConnected) return
        isConnected = false

        log(LogType.INFO, "TcpClient", "Disconnecting: $reason")
        cleanup()
        callback.onConnectionChanged(false)
    }

    private fun cleanup() {
        scope?.cancel()
        scope = null

        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}

        input = null
        output = null
        socket = null
    }
}