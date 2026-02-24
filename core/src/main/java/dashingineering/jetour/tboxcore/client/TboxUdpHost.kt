package dashingineering.jetour.tboxcore.client

import dashingineering.jetour.tboxcore.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class TboxUdpHost(
    private val coroutineScope: CoroutineScope,
    private val listeners: MutableList<TboxHostListener> = mutableListOf()
) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
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

    suspend fun start(ip: String, port: Int): Boolean {
        if (_isRunning) return true
        return try {
            socket = DatagramSocket().apply { soTimeout = 1000 }
            address = InetAddress.getByName(ip)
            _isRunning = true
            notifyListeners { onHostConnected() }
            startListener()
            true
        } catch (e: Exception) {
            log("ERROR", "UDP", "Start failed: ${e.message}")
            _isRunning = false
            false
        }
    }

    suspend fun stop() {
        if (!_isRunning) return
        listenJob?.cancel()
        listenJob = null
        socket?.close()
        socket = null
        address = null
        _isRunning = false
        notifyListeners { onHostDisconnected() }
    }

    suspend fun sendCommand(command: ByteArray): Boolean {
        if (!_isRunning || socket == null || address == null) return false
        return try {
            val packet = DatagramPacket(command, command.size, address, socket!!.localPort)
            mutex.withLock {
                socket!!.send(packet)
            }
            true
        } catch (e: Exception) {
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
                    // timeout or closed
                }
                delay(100)
            }
        }
    }

    private suspend fun log(level: String, tag: String, msg: String) {
        notifyListeners { onLog(level, tag, msg) }
    }

    private suspend fun notifyListeners(block: suspend TboxHostListener.() -> Unit) {
        // Копируем список, чтобы избежать ConcurrentModification
        val snapshot = listeners.toList()
        snapshot.forEach { listener ->
            try {
                listener.block()
            } catch (e: Exception) {
                // игнорируем мёртвые слушатели
            }
        }
    }
}