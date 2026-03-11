// service/TBoxBridgeService.kt
package dashingineering.jetour.tboxcore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClientCallback
import dashingineering.jetour.tboxcore.tcp.TcpServer
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
import dashingineering.jetour.tboxcore.util.toHex
import kotlinx.coroutines.*
import java.net.InetAddress

class TBoxBridgeService : Service() {

    companion object {
        const val ACTION_START = "dashingineering.jetour.tboxcore.service.START"
        const val ACTION_SEND = "dashingineering.jetour.tboxcore.ACTION_SEND"
        const val EXTRA_DATA = "data"  // 🔧 Добавили ключ для данных

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tcpServer: TcpServer? = null
    private var udpManager: UdpSocketManager? = null
    private var isForegroundStarted = false

    // 🔧 BroadcastReceiver для приёма команд от TBoxClient
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND) {
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getByteArrayExtra(EXTRA_DATA)
                } else {
                    @Suppress("DEPRECATION") intent.getByteArrayExtra(EXTRA_DATA)
                }

                if (data != null) {
                    log(LogType.DEBUG, "TBoxService", "← Received command via broadcast: ${data.toHex()} (${data.size} bytes)")

                    // Пересылаем в UDP
                    serviceScope.launch {
                        val sent = udpManager?.send(data) == true
                        log(LogType.DEBUG, "TBoxService", "→ Forwarded to UDP: $sent")
                    }
                }
            }
        }
    }

    private val bridgeCallback = object : TBoxClientCallback {
        override fun onDataReceived( data: ByteArray) {
            // UDP → TCP: рассылка клиентам
            tcpServer?.broadcastToClients(data)
            // Также передаём в коллбэк (сервер тоже "получает" данные)
            // (опционально, если нужно)
        }

        override fun onLogMessage(type: LogType, tag: String, message: String) {
            android.util.Log.println(type.ordinal + 2, tag, message)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Регистрируем BroadcastReceiver для приёма команд от TBoxClient
        val filter = IntentFilter(ACTION_SEND)

        // 🔧 Используем 4-параметрическую версию (совместима с core-ktx 1.12.0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,                    // context
                commandReceiver,         // receiver
                filter,                  // filter
                ContextCompat.RECEIVER_NOT_EXPORTED  // flags (последним!)
            )
        } else {
            // Для Android 9-12: используем стандартный registerReceiver
            // Без разрешения — достаточно для локальных сообщений
            registerReceiver(commandReceiver, filter, null, null)
        }

        log(LogType.INFO, "TBoxService", "Command receiver registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, DEFAULT_LOCAL_PORT)
            val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, DEFAULT_REMOTE_PORT)
            val addressStr = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: DEFAULT_REMOTE_ADDRESS
            val tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT)

            // 🔴 КРИТИЧНО: startForeground() должен быть вызван СИНХРОННО
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
                stopSelf()
                return
            }
            udpManager?.startReceiving()
            log(LogType.INFO, "TBoxService", "UDP manager started on port $localPort")

            tcpServer = TcpServer(tcpPort, udpManager!!, bridgeCallback)
            val tcpStarted = tcpServer?.start() == true
            if (!tcpStarted) {
                log(LogType.ERROR, "TBoxService", "TCP server failed to start")
                stopSelf()
                return
            }

            // 🔧 Теперь эта строка выполнится!
            log(LogType.INFO, "TBoxService", "TCP server started on port $tcpPort")
            log(LogType.INFO, "TBoxService", "Bridge fully started (TCP:$tcpPort ↔ UDP:$localPort)")

        } catch (e: Exception) {
            log(LogType.ERROR, "TBoxService", "Bridge start failed: ${e.javaClass.simpleName}: ${e.message}")
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

    override fun onDestroy() {
        super.onDestroy()

        // Отписываемся от BroadcastReceiver
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: IllegalArgumentException) {
            // Уже отписан — это нормально
            log(LogType.WARN, "TBoxService", "Receiver already unregistered")
        }

        // Отменяем корутины и останавливаем компоненты
        serviceScope.cancel()
        runBlocking {
            tcpServer?.stop()
            udpManager?.shutdown()
        }

        // Сбрасываем ссылки
        tcpServer = null
        udpManager = null
        isForegroundStarted = false

        log(LogType.INFO, "TBoxService", "Service destroyed")
    }

    private fun log(type: LogType, tag: String, message: String) {
        bridgeCallback.onLogMessage(type, tag, message)
    }
}