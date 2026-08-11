package dashingineering.jetour.tboxcore.tcp

import android.os.Handler
import android.os.Looper
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxCallback
import dashingineering.jetour.tboxcore.types.TBoxStatus
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
    private val callback: TBoxCallback
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var scope: CoroutineScope? = null
    private val sendMutex = Mutex()

    var isConnected: Boolean = false
        private set

    private fun onLogMessage(type: LogType, tag: String, message: String) {
        postToMain {
            callback.onLogMessage(type, tag, message)
        }
    }

    private fun onDataReceived(data: ByteArray) {
        // Постим в main поток для безопасной работы с UI
        // Это стандартная практика для Android callbacks
        postToMain {
            callback.onDataReceived(data)
        }
    }

    private fun onConnectionChanged(connected: Boolean) {
        postToMain {
            callback.onConnectionChanged(connected)
        }
    }

    private fun onStatusChanged(status: TBoxStatus) {
        postToMain {
            callback.onStatusChanged(status)
        }
    }

    suspend fun connect(): Boolean {
        if (isConnected) return true

        return withContext(Dispatchers.IO) {
            try {
                onLogMessage(LogType.INFO, "TcpClient", "Connecting to $host:$port")

                socket = Socket()
                socket?.connect(InetSocketAddress(host, port), 3000)
                socket?.soTimeout = 0

                input = DataInputStream(socket?.getInputStream())
                output = DataOutputStream(socket?.getOutputStream())

                isConnected = true
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

                scope?.launch {
                    receiveLoop()
                }

                onLogMessage(LogType.INFO, "TcpClient", "Connected successfully")
                onConnectionChanged(true)

                true

            } catch (e: Exception) {
                onLogMessage(LogType.ERROR, "TcpClient", "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
                false
            }
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65536)
        var offset = 0

        while (isConnected && socket?.isConnected == true) {
            try {
                // Блокирующий read - ждём данные без polling
                // input.available() может вернуть 0 даже если данные есть в пути
                val read = input?.read(buffer, offset, buffer.size - offset) ?: -1
                
                if (read > 0) {
                    offset += read

                    var processed = 0
                    while (processed < offset) {
                        when (val result = FrameCodec.decode(buffer, processed)) {
                            is FrameCodec.DecodeResult.Success -> {
                                when (result.type) {
                                    FrameCodec.TYPE_DATA -> onDataReceived(result.data)
                                    FrameCodec.TYPE_STATUS -> {
                                        val status = TBoxStatus.fromByteArray(result.data)
                                        if (status != null) {
                                            onStatusChanged(status)
                                        } else {
                                            onLogMessage(LogType.WARN, "TcpClient", "Failed to parse status frame")
                                        }
                                    }
                                    else -> onLogMessage(LogType.WARN, "TcpClient", "Unknown frame type: ${result.type}")
                                }
                                processed += result.consumed
                            }
                            is FrameCodec.DecodeResult.Incomplete -> {
                                break
                            }
                            is FrameCodec.DecodeResult.Error -> {
                                onLogMessage(LogType.ERROR, "TcpClient", "Decode error: ${result.message}")
                                disconnect("Decode error")
                                return
                            }
                        }
                    }

                    if (processed > 0 && processed < offset) {
                        val remaining = offset - processed
                        System.arraycopy(buffer, processed, buffer, 0, remaining)
                        offset = remaining
                    } else if (processed >= offset) {
                        offset = 0
                    }
                } else if (read == -1) {
                    disconnect("Server closed connection")
                    return
                }
                // Если read == 0 (неблокирующий режим) - продолжаем цикл без delay
            } catch (e: Exception) {
                if (isConnected) {
                    onLogMessage(LogType.ERROR, "TcpClient", "Receive error: ${e.javaClass.simpleName}: ${e.message}")
                    disconnect("Receive error")
                }
                return
            }
        }
    }

    suspend fun send( data: ByteArray): Boolean {
        if (!isConnected || output == null) {
            onLogMessage(LogType.WARN, "TcpClient", "Cannot send: not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            sendMutex.withLock {
                try {
                    val frame = FrameCodec.encode(data)
                    output?.write(frame)
                    output?.flush()
                    true
                } catch (e: Exception) {
                    onLogMessage(LogType.ERROR, "TcpClient", "Send failed: ${e.message}")
                    false
                }
            }
        }
    }

    fun disconnect(reason: String = "Manual") {
        if (!isConnected) return
        isConnected = false

        onLogMessage(LogType.INFO, "TcpClient", "Disconnecting: $reason")
        cleanup()
        onConnectionChanged(false)  // 🔴 Уже внутри postToMain
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun cleanup() {
        runBlocking(Dispatchers.IO) {
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
}