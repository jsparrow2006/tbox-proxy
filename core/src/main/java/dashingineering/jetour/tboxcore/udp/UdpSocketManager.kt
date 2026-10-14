package dashingineering.jetour.tboxcore.udp

import dashingineering.jetour.tboxcore.tcp.BridgeLogger
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Настройка сокета перед использованием, например `Network.bindSocket`. */
internal typealias SocketConfigurator = (DatagramSocket) -> Unit

/**
 * UDP-сокет моста. Сокет можно пересоздать ([reopen]) с другой привязкой к сети —
 * цикл приёма при этом не останавливается.
 */
internal class UdpSocketManager(
    private val localPort: Int,
    private val remotePort: Int,
    private val remoteAddress: InetAddress,
    private val log: BridgeLogger,
    /** Лог каждого пакета. Выключен по умолчанию: на ГУ это заметная нагрузка. */
    private val logPackets: Boolean = false,
    private val onData: (ByteArray) -> Unit
) {
    @Volatile
    private var socket: DatagramSocket? = null
    private val socketLock = Any()
    private val sendMutex = Mutex()
    private var scope: CoroutineScope? = null

    @Volatile
    var lastDataReceivedTime: Long = 0L
        private set

    /** Текущий сокет настроен переданным [SocketConfigurator] (привязан к сети TBox). */
    @Volatile
    var isConfigured: Boolean = false
        private set

    /** `false`, если порт занят. Ошибка настройки не фатальна — сокет остаётся без неё. */
    fun open(configure: SocketConfigurator? = null): Boolean = synchronized(socketLock) {
        val created = createSocket() ?: return false
        isConfigured = applyConfiguration(created, configure)
        socket = created
        log(LogType.INFO, TAG, "UDP bound on port $localPort")
        true
    }

    /**
     * Закрывает текущий сокет и открывает новый на том же порту с новой настройкой.
     * Порт UDP освобождается сразу после `close()`, поэтому повторный bind не конфликтует.
     */
    fun reopen(configure: SocketConfigurator?): Boolean = synchronized(socketLock) {
        socket?.close()
        socket = null
        isConfigured = false
        val created = createSocket() ?: return false
        isConfigured = applyConfiguration(created, configure)
        socket = created
        log(LogType.INFO, TAG, "UDP socket reopened on port $localPort, configured=$isConfigured")
        true
    }

    private fun createSocket(): DatagramSocket? = try {
        DatagramSocket(localPort).apply { soTimeout = RECEIVE_TIMEOUT_MS }
    } catch (e: Exception) {
        log(LogType.ERROR, TAG, "UDP bind on port $localPort failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun applyConfiguration(socket: DatagramSocket, configure: SocketConfigurator?): Boolean {
        if (configure == null) return false
        return try {
            configure(socket)
            true
        } catch (e: Exception) {
            log(LogType.ERROR, TAG, "UDP socket configuration failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun startReceiving() {
        if (scope != null) return
        val receiveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = receiveScope
        receiveScope.launch {
            val buffer = ByteArray(MAX_DATAGRAM)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                val current = socket
                if (current == null || current.isClosed) {
                    delay(SOCKET_WAIT_MS)
                    continue
                }
                try {
                    packet.setData(buffer, 0, buffer.size)
                    current.receive(packet)
                    val data = buffer.copyOfRange(0, packet.length)
                    lastDataReceivedTime = System.currentTimeMillis()
                    if (logPackets) {
                        log(LogType.DEBUG, TAG, "← UDP ${packet.address.hostAddress}:${packet.port} ${data.toLogString()}")
                    }
                    onData(data)
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (current === socket && isActive) {
                        log(LogType.ERROR, TAG, "UDP receive error: ${e.javaClass.simpleName}: ${e.message}")
                        delay(SOCKET_WAIT_MS)
                    }
                }
            }
        }
    }

    suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val current = socket ?: return@withLock false.also {
                log(LogType.ERROR, TAG, "UDP send skipped: no socket")
            }
            try {
                current.send(DatagramPacket(data, data.size, remoteAddress, remotePort))
                if (logPackets) {
                    log(LogType.DEBUG, TAG, "→ UDP ${remoteAddress.hostAddress}:$remotePort ${data.toLogString()}")
                }
                true
            } catch (e: Exception) {
                log(LogType.ERROR, TAG, "UDP send failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    fun shutdown() {
        scope?.cancel()
        scope = null
        synchronized(socketLock) {
            socket?.close()
            socket = null
        }
    }

    fun isRunning(): Boolean = scope != null && socket?.isClosed == false

    private companion object {
        const val TAG = "UdpSocketManager"
        const val RECEIVE_TIMEOUT_MS = 1000
        const val SOCKET_WAIT_MS = 200L
        const val MAX_DATAGRAM = 65_535
    }
}
