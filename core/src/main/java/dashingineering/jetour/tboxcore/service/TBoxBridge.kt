package dashingineering.jetour.tboxcore.service

import dashingineering.jetour.tboxcore.TBoxProxyInfo
import dashingineering.jetour.tboxcore.constants.TBoxConstants
import dashingineering.jetour.tboxcore.tcp.BridgeLogger
import dashingineering.jetour.tboxcore.tcp.TcpServer
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxHostInfo
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.types.TBoxWireProtocol
import dashingineering.jetour.tboxcore.udp.SocketConfigurator
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
import dashingineering.jetour.tboxcore.util.ByteConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Мост UDP (TBox) ↔ локальный TCP (приложения). Не зависит от Android:
 * привязку к сети передаёт сервис через [onNetworkChanged].
 */
internal class TBoxBridge(
    private val config: Config,
    private val log: BridgeLogger,
    private val onStatus: (TBoxStatus) -> Unit = {}
) {
    data class Config(
        val localPort: Int,
        val remotePort: Int,
        val remoteAddress: InetAddress,
        val legacyTcpPort: Int,
        val v3TcpPort: Int,
        val hostPackage: String,
        val logPackets: Boolean = false,
        val silenceThresholdMs: Long = 10_000L,
        val probeIntervalMs: Long = 10_000L,
        val probesBeforeReopen: Int = 3,
        val watchdogTickMs: Long = 1_000L
    )

    enum class StartResult { STARTED, UDP_BIND_FAILED, TCP_BIND_FAILED }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val networkLock = Any()

    @Volatile
    private var started = false

    private val udp = UdpSocketManager(
        localPort = config.localPort,
        remotePort = config.remotePort,
        remoteAddress = config.remoteAddress,
        log = log,
        logPackets = config.logPackets,
        onData = ::onUdpData
    )

    private val tcp = TcpServer(
        legacyPort = config.legacyTcpPort,
        v3Port = config.v3TcpPort,
        hostInfo = ::hostInfo,
        onClientData = { data -> udp.send(data) },
        log = log
    )

    @Volatile
    private var networkConfigurator: SocketConfigurator? = null

    @Volatile
    private var networkInterface: String? = null

    @Volatile
    private var silent = false

    fun hostInfo(): TBoxHostInfo = TBoxHostInfo(
        protocol = TBoxWireProtocol.V3,
        hostPackage = config.hostPackage,
        libraryVersion = TBoxProxyInfo.LIBRARY_VERSION,
        networkBound = udp.isConfigured,
        networkInterface = networkInterface.takeIf { udp.isConfigured }
    )

    fun start(): StartResult = synchronized(networkLock) {
        if (!udp.open(networkConfigurator)) {
            status(TBoxStatusType.UDP_BIND_FAILED, "UDP port ${config.localPort} is busy")
            return StartResult.UDP_BIND_FAILED
        }
        status(TBoxStatusType.UDP_BIND_SUCCESS, "UDP bound on port ${config.localPort}")
        if (!tcp.start()) {
            udp.shutdown()
            status(TBoxStatusType.TCP_SERVER_ERROR, "TCP port ${config.legacyTcpPort} is busy")
            return StartResult.TCP_BIND_FAILED
        }
        udp.startReceiving()
        started = true
        status(TBoxStatusType.TCP_SERVER_STARTED, "TCP legacy:${config.legacyTcpPort} v3:${config.v3TcpPort}")
        reportBinding()
        status(
            TBoxStatusType.SERVICE_STARTED,
            "Bridge ${TBoxProxyInfo.LIBRARY_VERSION} started (TCP:${config.legacyTcpPort}/${config.v3TcpPort} ↔ UDP:${config.localPort})"
        )
        scope.launch {
            sendProbe("wake-up")
            watchdog()
        }
        StartResult.STARTED
    }

    /**
     * Сеть TBox появилась, сменилась или пропала. `configurator == null` — сокет без привязки
     * (трафик идёт по маршруту по умолчанию). Если мост уже работает, сокет пересоздаётся,
     * а клиенты v3 получают новый HOST_INFO. До [start] значение просто запоминается.
     */
    fun onNetworkChanged(configurator: SocketConfigurator?, interfaceName: String?) {
        synchronized(networkLock) {
            networkConfigurator = configurator
            networkInterface = interfaceName
            if (!started) return
            udp.reopen(configurator)
            reportBinding()
            tcp.broadcastHostInfo()
        }
        scope.launch { sendProbe("network changed") }
    }

    /** Лог хоста для клиентов 2.1.x и 3.x (статус LOG), как в 2.1.x. */
    fun forwardLog(type: LogType, tag: String, message: String) {
        if (!started) return
        tcp.broadcastStatus(TBoxStatus(TBoxStatusType.LOG, "[$type/$tag] $message"))
    }

    fun stop() {
        synchronized(networkLock) {
            if (started) status(TBoxStatusType.SERVICE_STOPPED, "Bridge stopping")
            started = false
            scope.cancel()
            tcp.stop()
            udp.shutdown()
        }
    }

    val clientCount: Int get() = tcp.clientCount

    private fun reportBinding() {
        if (udp.isConfigured) {
            status(TBoxStatusType.NETWORK_BOUND, "UDP bound to TBox network ${networkInterface ?: "?"}")
        } else {
            status(TBoxStatusType.NETWORK_UNBOUND, "UDP uses default route")
        }
    }

    private fun onUdpData(data: ByteArray) {
        if (silent) {
            silent = false
            status(TBoxStatusType.TBOX_RESPONDING, "TBox is responding again")
        }
        tcp.broadcastData(data)
    }

    private suspend fun watchdog() {
        val startedAt = System.currentTimeMillis()
        var lastProbeAt = System.currentTimeMillis()
        var probes = 0
        while (scope.isActive) {
            delay(config.watchdogTickMs)
            val now = System.currentTimeMillis()
            val lastRx = udp.lastDataReceivedTime.takeIf { it > 0 } ?: startedAt
            val silence = now - lastRx
            if (silence < config.silenceThresholdMs) {
                probes = 0
                continue
            }
            if (!silent) {
                silent = true
                status(TBoxStatusType.TBOX_SILENT, "TBox silent for ${silence / 1000}s")
            }
            if (now - lastProbeAt < config.probeIntervalMs) continue
            lastProbeAt = now
            probes++
            if (probes > config.probesBeforeReopen) {
                probes = 0
                synchronized(networkLock) {
                    if (!started) return
                    log(LogType.WARN, TAG, "No answer to probes, reopening UDP socket")
                    udp.reopen(networkConfigurator)
                    reportBinding()
                    tcp.broadcastHostInfo()
                }
            }
            sendProbe("silence ${silence / 1000}s")
        }
    }

    /** CRT getHW — тот же запрос, что 2.1.x шлёт при старте; ответ получат все клиенты. */
    private suspend fun sendProbe(reason: String) {
        val data = byteArrayOf(0x00, 0x00, 0x01, 0x05)
        val header = ByteConverter.fillHeader(data.size, TBoxConstants.CRT_CODE, TBoxConstants.SELF_CODE, 0x12)
        val packet = header + data + ByteConverter.xorSum(header + data)
        val sent = udp.send(packet)
        log(if (sent) LogType.INFO else LogType.WARN, TAG, "Probe ($reason) sent=$sent")
    }

    private fun status(type: TBoxStatusType, message: String) {
        val status = TBoxStatus(type, message)
        onStatus(status)
        tcp.broadcastStatus(status)
    }

    private companion object {
        const val TAG = "TBoxBridge"
    }
}
