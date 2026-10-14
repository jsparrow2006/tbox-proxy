package dashingineering.jetour.tboxcore

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dashingineering.jetour.tboxcore.service.TBoxBridgeService
import dashingineering.jetour.tboxcore.tcp.BridgeConnection
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxClientCallback
import dashingineering.jetour.tboxcore.types.TBoxCommand
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import dashingineering.jetour.tboxcore.util.ByteConverter
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import dashingineering.jetour.tboxcore.util.TBoxReceivedMessage
import dashingineering.jetour.tboxcore.util.startForegroundServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 🎯 Главная точка входа в библиотеку TBoxCore.
 *
 * Порядок подключения:
 * 1. Хост 3.x на порту [v3TcpPort] → рукопожатие, полный функционал.
 * 2. Хост 1.x/2.x на порту [tcpPort] → режим совместимости (статус [TBoxStatusType.LEGACY_HOST]):
 *    UDP-сокет хоста не привязан к сети TBox, статусов v3 и [getHostInfo] с деталями нет.
 * 3. Хоста нет → запускается [TBoxBridgeService] в этом приложении, клиент подключается к нему.
 *
 * Колбэки вызываются в главном потоке.
 *
 * @param localPort локальный UDP-порт хоста (по умолчанию 11048)
 * @param remotePort UDP-порт TBox (по умолчанию 50047)
 * @param remoteAddress IP TBox (по умолчанию 192.168.225.1)
 * @param tcpPort порт для клиентов 1.x/2.x (по умолчанию 1104)
 * @param host адрес TCP-моста (по умолчанию 127.0.0.1)
 * @param v3TcpPort порт протокола v3 (по умолчанию 1105)
 * @param bindToTBoxNetwork хост привязывает UDP-сокет к сети TBox (`Network.bindSocket`),
 *   чтобы трафик не уходил в Wi-Fi
 */
class TBoxClient(
    private val context: Context,
    private val localPort: Int = DEFAULT_LOCAL_PORT,
    private val remotePort: Int = DEFAULT_REMOTE_PORT,
    private val remoteAddress: String = DEFAULT_REMOTE_ADDRESS,
    private val tcpPort: Int = DEFAULT_TCP_PORT,
    private val host: String = DEFAULT_HOST,
    private val callback: TBoxClientCallback,
    private val v3TcpPort: Int = DEFAULT_V3_TCP_PORT,
    private val bindToTBoxNetwork: Boolean = true
) {

    companion object {
        const val DEFAULT_LOCAL_PORT = 11048
        const val DEFAULT_REMOTE_PORT = 50047
        const val DEFAULT_REMOTE_ADDRESS = "192.168.225.1"
        const val DEFAULT_TCP_PORT = 1104
        const val DEFAULT_V3_TCP_PORT = 1105
        const val DEFAULT_HOST = "127.0.0.1"
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val RECONNECT_JITTER_MS = 500L
        private const val HOST_START_POLL_MS = 250L
        private const val HOST_START_ATTEMPTS = 20
        private const val TAG = "TBoxClient"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var connection: BridgeConnection? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isPhysicallyConnected = false

    @Volatile
    private var startedService = false

    private var connectJob: Job? = null
    private var sendQueue: Channel<ByteArray>? = null

    fun initialize() {
        if (isInitialized) {
            log(LogType.WARN, TAG, "Already initialized, ignoring duplicate init")
            return
        }
        isInitialized = true
        val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = clientScope
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        sendQueue = queue
        clientScope.launch { sendLoop(queue) }

        log(
            LogType.INFO, TAG,
            "Initialized ${TBoxProxyInfo.LIBRARY_VERSION}: UDP:$localPort → $remoteAddress:$remotePort, TCP:$tcpPort/v3:$v3TcpPort"
        )
        connectJob = clientScope.launch { connect() }
    }

    private suspend fun connect() {
        val listener = ConnectionListener()

        BridgeConnection.connectV3(host, v3TcpPort, context.packageName, ::log, listener)?.let {
            onConnected(it, listener, "v3 host")
            return
        }
        BridgeConnection.connectLegacy(host, tcpPort, ::log, listener)?.let {
            onConnected(it, listener, "legacy host")
            return
        }

        log(LogType.INFO, TAG, "No host on $host:$tcpPort/$v3TcpPort, starting local bridge")
        startBridgeService()
        repeat(HOST_START_ATTEMPTS) {
            delay(HOST_START_POLL_MS)
            if (!isInitialized) return
            BridgeConnection.connectV3(host, v3TcpPort, context.packageName, ::log, listener)?.let {
                onConnected(it, listener, "local bridge")
                return
            }
        }
        log(LogType.WARN, TAG, "Local bridge did not come up, retrying discovery")
        scheduleReconnect()
    }

    private fun onConnected(created: BridgeConnection, listener: ConnectionListener, description: String) {
        if (!isInitialized) {
            created.close()
            return
        }
        listener.connection = created
        connection = created
        if (!created.isConnected) {
            connection = null
            scheduleReconnect()
            return
        }
        log(LogType.INFO, TAG, "Connected to $description")
        postStatus(TBoxStatus(TBoxStatusType.CONNECTING, "TCP connected, waiting for TBox..."))
    }

    private fun startBridgeService() {
        val intent = Intent(context, TBoxBridgeService::class.java).apply {
            action = TBoxBridgeService.ACTION_START
            putExtra(TBoxBridgeService.EXTRA_LOCAL_PORT, localPort)
            putExtra(TBoxBridgeService.EXTRA_REMOTE_PORT, remotePort)
            putExtra(TBoxBridgeService.EXTRA_REMOTE_ADDRESS, remoteAddress)
            putExtra(TBoxBridgeService.EXTRA_TCP_PORT, tcpPort)
            putExtra(TBoxBridgeService.EXTRA_V3_TCP_PORT, v3TcpPort)
            putExtra(TBoxBridgeService.EXTRA_BIND_TO_TBOX_NETWORK, bindToTBoxNetwork)
        }
        try {
            context.startForegroundServiceCompat(intent)
            startedService = true
        } catch (e: Exception) {
            log(LogType.ERROR, TAG, "Failed to start bridge service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        val clientScope = scope ?: return
        if (!isInitialized) return
        connectJob?.cancel()
        connectJob = clientScope.launch {
            delay(RECONNECT_DELAY_MS + Random.nextLong(RECONNECT_JITTER_MS))
            if (!isInitialized) return@launch
            log(LogType.INFO, TAG, "Attempting reconnect...")
            connect()
        }
    }

    private fun setPhysicallyConnected(connected: Boolean, message: String) {
        if (isPhysicallyConnected == connected) return
        isPhysicallyConnected = connected
        postStatus(
            TBoxStatus(if (connected) TBoxStatusType.CONNECTED else TBoxStatusType.DISCONNECTED, message)
        )
        mainHandler.post { callback.onConnectionChanged(connected) }
    }

    private inner class ConnectionListener : BridgeConnection.Listener {
        @Volatile
        var connection: BridgeConnection? = null

        private fun isCurrent(): Boolean = connection != null && connection === this@TBoxClient.connection

        override fun onData(data: ByteArray) {
            setPhysicallyConnected(true, "TBox is responding")
            val message = TBoxReceivedMessage(data)
            mainHandler.post { callback.onDataReceived(message) }
        }

        override fun onStatus(status: TBoxStatus) {
            when (status.type) {
                TBoxStatusType.LOG -> mainHandler.post {
                    callback.onLogMessage(LogType.DEBUG, "Service", status.message)
                }
                TBoxStatusType.TBOX_SILENT -> {
                    setPhysicallyConnected(false, status.message)
                    postStatus(status)
                }
                else -> postStatus(status)
            }
        }

        override fun onHostInfo(info: TBoxHostInfo) {
            if (info.isLegacy) {
                postStatus(
                    TBoxStatus(
                        TBoxStatusType.LEGACY_HOST,
                        "Host uses tbox-proxy 1.x/2.x (${info.protocol}): no TBox network binding, no v3 statuses",
                        info.protocol.name
                    )
                )
            } else {
                log(
                    LogType.INFO, TAG,
                    "Host ${info.hostPackage} lib=${info.libraryVersion} bound=${info.networkBound} iface=${info.networkInterface}"
                )
            }
        }

        override fun onClosed(reason: String) {
            if (!isCurrent()) return
            this@TBoxClient.connection = null
            log(LogType.INFO, TAG, "Connection lost: $reason")
            setPhysicallyConnected(false, "Connection lost")
            if (isInitialized) scheduleReconnect()
        }
    }

    private suspend fun sendLoop(queue: Channel<ByteArray>) {
        for (data in queue) {
            val current = connection
            if (current == null || !current.isConnected) {
                log(LogType.WARN, TAG, "Not connected, dropping ${data.size} bytes")
                continue
            }
            if (current.send(data)) {
                log(LogType.DEBUG, TAG, "→ Sent via TCP: ${data.toLogString()}")
            } else {
                log(LogType.ERROR, TAG, "Failed to send data")
            }
        }
    }

    /**
     * Отправляет raw-данные в TBox через мост. Асинхронно, порядок вызовов сохраняется.
     * Без подключения к хосту данные отбрасываются (как в 2.x).
     */
    fun sendRawMessage(data: ByteArray) {
        val current = connection
        if (current == null || !current.isConnected) {
            log(LogType.WARN, TAG, "Not connected, cannot send")
            return
        }
        if (sendQueue?.trySend(data)?.isSuccess != true) {
            log(LogType.ERROR, TAG, "Failed to queue data for sending")
        }
    }

    fun sendCommand(tid: Byte, sid: Byte, cmd: Byte, data: ByteArray) {
        val fullData = ByteConverter.fillHeader(data.size, tid, sid, cmd) + data
        val checksum = ByteConverter.xorSum(fullData)
        sendRawMessage(fullData + checksum)
    }

    fun sendCommand(command: TBoxCommand) {
        log(
            LogType.INFO, TAG,
            "${command.textMessage} Sending command: tid: ${command.tid} sid: ${command.sid} cmd: ${command.cmd} data: ${command.data.toLogString()} "
        )
        sendCommand(command.tid, command.sid, command.cmd, command.data)
    }

    /** `true` после первого пакета от TBox и до потери связи или долгого молчания TBox. */
    fun isConnected(): Boolean = isPhysicallyConnected

    /** `SERVER` — хост моста работает в этом приложении. */
    fun getMode(): String = if (isHostedHere()) "SERVER" else "CLIENT"

    /** Сведения о хосте; `null`, пока нет соединения. Для хостов 1.x/2.x известен только протокол. */
    fun getHostInfo(): TBoxHostInfo? = connection?.hostInfo

    /** Протокол обмена с текущим хостом; `null`, пока нет соединения или формат ещё не определён. */
    fun getWireProtocol(): TBoxWireProtocol? = getHostInfo()?.protocol

    private fun isHostedHere(): Boolean = getHostInfo()?.hostPackage == context.packageName

    fun destroy() {
        if (!isInitialized) return
        log(LogType.INFO, TAG, "Destroying...")
        val hostedHere = isHostedHere() || startedService
        isInitialized = false
        isPhysicallyConnected = false
        connectJob?.cancel()
        connectJob = null
        sendQueue?.close()
        sendQueue = null
        val current = connection
        connection = null
        current?.close()
        scope?.cancel()
        scope = null

        if (hostedHere) {
            try {
                context.stopService(Intent(context, TBoxBridgeService::class.java))
            } catch (e: Exception) {
                log(LogType.WARN, TAG, "Failed to stop service: ${e.message}")
            }
        }
        startedService = false
        log(LogType.INFO, TAG, "Destroyed complete")
    }

    private fun postStatus(status: TBoxStatus) {
        mainHandler.post { callback.onStatusChanged(status) }
    }

    private fun log(type: LogType, tag: String, message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback.onLogMessage(type, tag, message)
        } else {
            mainHandler.post { callback.onLogMessage(type, tag, message) }
        }
    }
}
