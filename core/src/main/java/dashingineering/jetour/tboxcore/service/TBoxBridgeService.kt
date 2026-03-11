package dashingineering.jetour.tboxcore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClientCallback
import dashingineering.jetour.tboxcore.tcp.TcpServer
import dashingineering.jetour.tboxcore.udp.UdpSocketManager
import kotlinx.coroutines.*
import java.net.InetAddress

/**
 * Foreground Service который держит TCP-сервер и UDP-сокет
 *
 * ⚠️ ВАЖНО: startForeground() должен быть вызван синхронно в onStartCommand()
 * в течение 5 секунд после старта сервиса (требование Android 8.0+)
 */
class TBoxBridgeService : Service() {

    companion object {
        const val ACTION_START = "dashingineering.jetour.tboxcore.service.START"
        const val ACTION_SEND = "dashingineering.jetour.tboxcore.ACTION_SEND"

        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_REMOTE_PORT = "remote_port"
        const val EXTRA_REMOTE_ADDRESS = "remote_address"
        const val EXTRA_TCP_PORT = "tcp_port"

        // Значения по умолчанию
        const val DEFAULT_LOCAL_PORT = 11048
        const val DEFAULT_REMOTE_PORT = 50047
        const val DEFAULT_REMOTE_ADDRESS = "192.168.225.1"
        const val DEFAULT_TCP_PORT = 1104

        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "TBoxBridgeChannel"
    }

    // Корутин-скоуп для асинхронных операций
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Компоненты моста
    private var tcpServer: TcpServer? = null
    private var udpManager: UdpSocketManager? = null

    // Флаг: был ли уже вызван startForeground
    private var isForegroundStarted = false

    // Внутренний коллбэк для обработки событий
    private val bridgeCallback = object : TBoxClientCallback {
        override fun onDataReceived(data: ByteArray) {
            // Данные из UDP уже рассылаются TcpServer, здесь можно добавить логику
        }

        override fun onLogMessage(type: LogType, tag: String, message: String) {
            // Логи сервиса — используем стандартный Android Log
            android.util.Log.println(type.ordinal + 2, tag, message)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Создаём канал уведомлений заранее (это быстро и безопасно)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            // Получаем параметры с дефолтными значениями
            val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, DEFAULT_LOCAL_PORT)
            val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, DEFAULT_REMOTE_PORT)
            val addressStr = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: DEFAULT_REMOTE_ADDRESS
            val tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT)

            // 🔴 КРИТИЧНО: Вызываем startForeground() СИНХРОННО и НЕМЕДЛЕННО
            // Это должно произойти в течение 5 секунд после входа в onStartCommand()
            if (!isForegroundStarted) {
                startForeground(NOTIFICATION_ID, buildNotification())
                isForegroundStarted = true
            }

            // Теперь можно запускать тяжёлую инициализацию в корутине
            serviceScope.launch {
                startBridge(localPort, remotePort, addressStr, tcpPort)
            }
        }
        return START_STICKY
    }

    /**
     * Асинхронная инициализация моста (TCP ↔ UDP)
     * Вызывается из корутины, поэтому может содержать suspend-функции
     */
    private suspend fun startBridge(
        localPort: Int,
        remotePort: Int,
        addressStr: String,
        tcpPort: Int
    ) {
        try {
            bridgeCallback.onLogMessage(LogType.INFO, "TBoxService", "Starting bridge initialization...")

            // 1. Инициализируем UDP-менеджер
            val address = InetAddress.getByName(addressStr)
            udpManager = UdpSocketManager(localPort, remotePort, address, bridgeCallback)

            if (!udpManager?.initialize()!!) {
                bridgeCallback.onLogMessage(LogType.ERROR, "TBoxService", "UDP initialization failed")
                stopSelf()
                return
            }
            udpManager?.startReceiving()
            bridgeCallback.onLogMessage(LogType.INFO, "TBoxService", "UDP manager started on port $localPort")

            // 2. Запускаем TCP-сервер с мостом к UDP
            tcpServer = TcpServer(tcpPort, udpManager!!, bridgeCallback)
            val tcpStarted = tcpServer?.start() == true
            if (!tcpStarted) {
                bridgeCallback.onLogMessage(LogType.ERROR, "TBoxService", "TCP server failed to start")
                stopSelf()
                return
            }
            bridgeCallback.onLogMessage(LogType.INFO, "TBoxService", "TCP server started on port $tcpPort")

            // 3. Готово
            bridgeCallback.onLogMessage(LogType.INFO, "TBoxService", "Bridge fully started (TCP:$tcpPort ↔ UDP:$localPort)")

        } catch (e: Exception) {
            bridgeCallback.onLogMessage(LogType.ERROR, "TBoxService", "Bridge start failed: ${e.message}", e)
            stopSelf()
        }
    }

    /**
     * Создание канала уведомлений (для Android 8.0+)
     * Вызывается в onCreate() — это быстро и безопасно
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TBox Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TCP/UDP bridge service for inter-app communication"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Построение уведомления для foreground-режима
     * Должно выполняться быстро (без тяжелых операций)
     */
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

        // Отменяем все корутины
        serviceScope.cancel()

        // Останавливаем компоненты в правильном порядке
        runBlocking {
            tcpServer?.stop()
            udpManager?.shutdown()
        }

        // Сбрасываем ссылки
        tcpServer = null
        udpManager = null
        isForegroundStarted = false

        bridgeCallback.onLogMessage(LogType.INFO, "TBoxService", "Service destroyed")
    }
}

// Extension-функция для логирования с Throwable
private fun TBoxClientCallback.onLogMessage(type: LogType, tag: String, message: String, throwable: Throwable? = null) {
    val fullMessage = if (throwable != null) {
        "$message\n${android.util.Log.getStackTraceString(throwable)}"
    } else {
        message
    }
    onLogMessage(type, tag, fullMessage)
}