package dashingineering.jetour.tboxcore.tcp

import dashingineering.jetour.tboxcore.types.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP-соединение с хостом моста. Отдаёт тела кадров ([FrameReader]) и не знает о формате —
 * его интерпретирует [BridgeConnection]. Колбэки приходят в потоке ввода-вывода.
 */
internal class TcpClient(
    private val host: String,
    private val port: Int,
    private val log: BridgeLogger,
    private val onBody: (ByteArray) -> Unit,
    private val onClosed: (reason: String) -> Unit
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isConnected: Boolean = false
        private set

    suspend fun connect(timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val created = Socket()
            created.connect(InetSocketAddress(host, port), timeoutMs)
            created.tcpNoDelay = true
            socket = created
            output = created.getOutputStream()
            isConnected = true
            scope.launch { readLoop(created) }
            true
        } catch (e: Exception) {
            log(LogType.DEBUG, TAG, "Connect $host:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            close(notify = false, reason = "connect failed")
            false
        }
    }

    private fun readLoop(socket: Socket) {
        val reader = FrameReader()
        val chunk = ByteArray(READ_CHUNK)
        var reason = "Server closed connection"
        try {
            val input = socket.getInputStream()
            while (!closed.get()) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                for (body in reader.append(chunk, read)) onBody(body)
            }
        } catch (e: FrameReader.FrameException) {
            reason = "Protocol error: ${e.message}"
            log(LogType.ERROR, TAG, reason)
        } catch (e: Exception) {
            reason = "Receive error: ${e.javaClass.simpleName}: ${e.message}"
        }
        close(notify = true, reason = reason)
    }

    /** Отправляет уже закодированный кадр. */
    suspend fun send(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val out = output ?: return@withLock false
            try {
                out.write(frame)
                out.flush()
                true
            } catch (e: Exception) {
                log(LogType.ERROR, TAG, "Send failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    fun disconnect() = close(notify = false, reason = "Manual")

    private fun close(notify: Boolean, reason: String) {
        if (!closed.compareAndSet(false, true)) return
        val wasConnected = isConnected
        isConnected = false
        runCatching { socket?.close() }
        socket = null
        output = null
        scope.cancel()
        if (notify && wasConnected) onClosed(reason)
    }

    private companion object {
        const val TAG = "TcpClient"
        const val READ_CHUNK = 16 * 1024
    }
}
