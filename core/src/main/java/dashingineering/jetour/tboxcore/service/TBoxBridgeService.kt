package dashingineering.jetour.tboxcore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dashingineering.jetour.tboxcore.constants.TBoxConstants
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxCallback
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.tcp.TcpServer
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
import dashingineering.jetour.tboxcore.util.ByteConverter
import kotlinx.coroutines.*
import java.net.InetAddress

class TBoxBridgeService : Service() {
    companion object {
        const val ACTION_START = "dashingineering.jetour.tboxcore.service.START"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_REMOTE_PORT = "remote_port"
        const val EXTRA_REMOTE_ADDRESS = "remote_address"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val DEFAULT_LOCAL_PORT = 11048
        const val DEFAULT_REMOTE_PORT = 50047
        const val DEFAULT_REMOTE_ADDRESS = "192.168.225.1"
        const val DEFAULT_TCP_PORT = 1104
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "TBoxBridgeChannel"
        private const val TBOX_TIMEOUT_MS = 5000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1000L
        private const val WATCHDOG_INITIAL_GRACE_MS = 5000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tcpServer: TcpServer? = null
    private var udpManager: UdpSocketManager? = null
    private var isForegroundStarted = false
    private var watchdogJob: Job? = null

    private val bridgeCallback = object : TBoxCallback {
        override fun onDataReceived(data: ByteArray) {
            tcpServer?.broadcastToClients(data)
        }
        override fun onLogMessage(type: LogType, tag: String, message: String) {
            android.util.Log.println(type.ordinal + 2, tag, message)
            tcpServer?.broadcastStatus(TBoxStatus(TBoxStatusType.LOG, "[$tag] $message"))
        }
        override fun onStatusChanged(status: TBoxStatus) {
            tcpServer?.broadcastStatus(status)
            android.util.Log.println(android.util.Log.INFO, "TBoxService", "Status: ${status.type} - ${status.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        log(LogType.INFO, "TBoxService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, DEFAULT_LOCAL_PORT)
            val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, DEFAULT_REMOTE_PORT)
            val addressStr = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: DEFAULT_REMOTE_ADDRESS
            val tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT)

            if (!isForegroundStarted) {
                startForeground(NOTIFICATION_ID, buildNotification())
                isForegroundStarted = true
            }

            serviceScope.launch {
                startBridge(localPort, remotePort, addressStr, tcpPort)
            }
        }
        return START_STICKY
    }

    private suspend fun startBridge(
        localPort: Int,
        remotePort: Int,
        addressStr: String,
        tcpPort: Int
    ) {
        try {
            log(LogType.INFO, "TBoxService", "Starting bridge initialization...")

            val address = InetAddress.getByName(addressStr)
            udpManager = UdpSocketManager(localPort, remotePort, address, bridgeCallback)

            if (!udpManager?.initialize()!!) {
                log(LogType.ERROR, "TBoxService", "UDP initialization failed")
                bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.UDP_BIND_FAILED, "UDP init failed on port $localPort"))
                stopSelf()
                return
            }
            bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.UDP_BIND_SUCCESS, "UDP bound on port $localPort"))
            udpManager?.startReceiving()
            log(LogType.INFO, "TBoxService", "UDP manager started on port $localPort")

            tcpServer = TcpServer(tcpPort, udpManager!!, bridgeCallback)
            val tcpStarted = tcpServer?.start() == true
            if (!tcpStarted) {
                log(LogType.ERROR, "TBoxService", "TCP server failed to start")
                bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.TCP_SERVER_ERROR, "TCP server failed on port $tcpPort"))
                stopSelf()
                return
            }

            bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.TCP_SERVER_STARTED, "TCP server on port $tcpPort"))
            bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.SERVICE_STARTED, "Bridge fully started (TCP:$tcpPort ↔ UDP:$localPort)"))
            log(LogType.INFO, "TBoxService", "TCP server started on port $tcpPort")
            log(LogType.INFO, "TBoxService", "Bridge fully started (TCP:$tcpPort ↔ UDP:$localPort)")

            sendWakeUpCommand()

            startWatchdog()

        } catch (e: Exception) {
            log(LogType.ERROR, "TBoxService", "Bridge start failed: ${e.javaClass.simpleName}: ${e.message}")
            bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.SERVICE_ERROR, "Bridge start failed", e.message))
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TBox Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TCP/UDP bridge service"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TBox Bridge Active")
            .setContentText("Network bridge running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun sendWakeUpCommand() {
        val data = byteArrayOf(0x00, 0x00, 0x01, 0x05)
        val header = ByteConverter.fillHeader(data.size, TBoxConstants.CRT_CODE, TBoxConstants.SELF_CODE, 0x12)
        val packet = header + data + ByteConverter.xorSum(header + data)

        log(LogType.INFO, "TBoxService", "Sending wake-up command (getHW)")
        val sent = udpManager?.send(packet) == true
        if (sent) {
            log(LogType.INFO, "TBoxService", "Wake-up command sent")
        } else {
            log(LogType.WARN, "TBoxService", "Failed to send wake-up command")
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            val startTime = System.currentTimeMillis()
            delay(WATCHDOG_INITIAL_GRACE_MS)
            log(LogType.INFO, "TBoxService", "Watchdog started, timeout=${TBOX_TIMEOUT_MS}ms")
            while (isActive) {
                val lastReceived = udpManager?.lastDataReceivedTime ?: 0L
                val elapsed = if (lastReceived > 0) {
                    System.currentTimeMillis() - lastReceived
                } else {
                    System.currentTimeMillis() - startTime
                }
                if (elapsed > TBOX_TIMEOUT_MS) {
                    log(LogType.WARN, "TBoxService", "TBox not responding, silence=${elapsed}ms")
                    bridgeCallback.onStatusChanged(
                        TBoxStatus(TBoxStatusType.UDP_RECEIVE_ERROR, "TBox not responding (${elapsed}ms silence)")
                    )
                    stopSelf()
                    return@launch
                }
                delay(WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        watchdogJob?.cancel()
        bridgeCallback.onStatusChanged(TBoxStatus(TBoxStatusType.SERVICE_STOPPED, "Service shutting down"))

        serviceScope.cancel()
        runBlocking {
            tcpServer?.stop()
            udpManager?.shutdown()
        }

        tcpServer = null
        udpManager = null
        isForegroundStarted = false

        log(LogType.INFO, "TBoxService", "Service destroyed")
    }

    private fun log(type: LogType, tag: String, message: String) {
        bridgeCallback.onLogMessage(type, tag, message)
    }
}