package dashingineering.jetour.tboxcore.client

import android.os.RemoteException
import android.util.Log
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class TboxUdpHost(
    private val coroutineScope: CoroutineScope,
    private val defaultIp: String = "192.168.225.1",
    private val defaultPort: Int = 50047,
    private val listeners: MutableList<TboxHostListener> = mutableListOf(),
) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var remotePort: Int = -1
    private val mutex = Mutex()
    private var listenJob: Job? = null
    private var _isRunning = false

    val isRunning: Boolean get() = _isRunning

    fun addListener(listener: TboxHostListener) {
        listeners += listener
    }

    fun removeListener(listener: TboxHostListener) {
        listeners -= listener
    }

    suspend fun start(ip: String? = null, port: Int? = null): Boolean {
        val actualIp = ip ?: defaultIp
        val actualPort = port ?: defaultPort

        return mutex.withLock {
            if (_isRunning) {
                notifyListeners { onLog("INFO", "UDP", "Хост уже запущен, подключение не требуется") }
                return@withLock true
            }
            return@withLock try {
                socket = DatagramSocket().apply { soTimeout = 200 }
                address = InetAddress.getByName(actualIp)
                remotePort = actualPort
                _isRunning = true
                notifyListeners { onLog("INFO", "UDP", "Сокет создан: $actualIp:$actualPort (UDP connectionless)") }
                startListener()
                true
            } catch (e: Exception) {
                notifyListeners { onLog("ERROR", "UDP", "Ошибка создания сокета $actualIp:$actualPort: ${e.message}") }
                _isRunning = false
                false
            }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            if (!_isRunning) return@withLock
            listenJob?.cancel()
            listenJob = null
            socket?.close()
            socket = null
            address = null
            remotePort = -1
            _isRunning = false
            notifyListeners { onHostDisconnected() }
        }
    }

    suspend fun sendCommand(command: ByteArray): Boolean {
        if (!_isRunning || socket == null || address == null || remotePort == -1) return false
        return try {
            // Используем remotePort вместо socket.localPort
            notifyListeners { onLog("INFO", "UDP", "Sending packet $command") }
            val packet = DatagramPacket(command, command.size, address, remotePort)
            mutex.withLock {
                socket!!.send(packet)
            }
            true
        } catch (e: Exception) {
            notifyListeners { onLog("ERROR", "UDP", "Send failed: ${e.message}") }
            log("ERROR", "UDP", "Send failed: ${e.message}")
            false
        }
    }

    private fun startListener() {
        listenJob = coroutineScope.launch {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    socket?.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    if (checkPacket(data)) {
                        val len = extractDataLength(data)
                        if (checkLength(data, len)) {
                            val payload = extractData(data, len)
                            if (payload.isNotEmpty()) {
                                notifyListeners { onDataReceived(payload) }
                            }
                        }
                    }
                } catch (ignored: Exception) {
                    // timeout or closed — soTimeout = 200ms, поэтому не блокируем надолго
                }
            }
        }
    }

    private suspend fun log(level: String, tag: String, msg: String) {
        notifyListeners { onLog(level, tag, msg) }
    }

    private suspend fun notifyListeners(block: suspend TboxHostListener.() -> Unit) {
        val snapshot = listeners.toList()
        snapshot.forEach { listener ->
            try {
                listener.block()
            } catch (e: Exception) {
                // ignore dead listeners
            }
        }
    }
}