package dashingineering.jetour.tboxcore.udp

import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClientCallback
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Простой UDP-менеджер для работы с сырыми ByteArray
 * Без парсинга протокола — вся логика на стороне клиента
 */
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

    /**
     * Инициализация сокета
     */

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

//    fun initialize(): Boolean {
//        return try {
//            socket = DatagramSocket(localPort).apply {
//                soTimeout = 1000 // 1 секунда таймаут для receive
//                reuseAddress = true
//            }
//            log(LogType.INFO, "UdpSocketManager", "Initialized on port $localPort")
//            true
//        } catch (e: SocketException) {
//            log(LogType.ERROR, "UdpSocketManager", "Failed to bind port $localPort: ${e.message}")
//            false
//        }
//    }

    /**
     * Запуск цикла приёма
     */
    fun startReceiving() {
        if (isReceiving) return
        isReceiving = true

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope?.launch {
            val buffer = ByteArray(4096) // Буфер под ваши пакеты

            while (scope?.isActive == true && isReceiving) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    // Копируем полученные данные (важно: packet.length может быть меньше buffer.size)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

                    callback.onLogMessage(
                        LogType.DEBUG,
                        "UdpSocketManager",
                        "← UDP received ${data.size} bytes from ${packet.address}:${packet.port}: ${data.toLogString()}"
                    )

                    // Передаём в коллбэк без изменений
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

    /**
     * Отправка сырых данных
     * @param data байты для отправки (клиент сам формирует пакет)
     * @return успешность отправки
     */
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

    /**
     * Остановка и очистка
     */
    fun shutdown() {
        isReceiving = false
        scope?.cancel()
        socket?.close()
        socket = null
        log(LogType.INFO, "UdpSocketManager", "Shutdown complete")
    }

    /**
     * Проверка активности
     */
    fun isRunning(): Boolean = socket?.isClosed == false && isReceiving
}