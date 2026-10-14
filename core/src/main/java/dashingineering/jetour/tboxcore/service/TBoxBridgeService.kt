package dashingineering.jetour.tboxcore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dashingineering.jetour.tboxcore.net.TBoxNetworkTracker
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxStatus
import java.net.InetAddress

/**
 * Foreground-сервис хоста моста. Запускается [dashingineering.jetour.tboxcore.TBoxClient],
 * если хоста нет. Вся логика — в [TBoxBridge].
 */
class TBoxBridgeService : Service() {
    companion object {
        const val ACTION_START = "dashingineering.jetour.tboxcore.service.START"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_REMOTE_PORT = "remote_port"
        const val EXTRA_REMOTE_ADDRESS = "remote_address"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_V3_TCP_PORT = "v3_tcp_port"
        const val EXTRA_BIND_TO_TBOX_NETWORK = "bind_to_tbox_network"
        const val DEFAULT_LOCAL_PORT = 11048
        const val DEFAULT_REMOTE_PORT = 50047
        const val DEFAULT_REMOTE_ADDRESS = "192.168.225.1"
        const val DEFAULT_TCP_PORT = 1104
        const val DEFAULT_V3_TCP_PORT = 1105
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "TBoxBridgeChannel"
        private const val LOG_TAG = "TBoxBridge"
    }

    private var bridge: TBoxBridge? = null
    private var networkTracker: TBoxNetworkTracker? = null
    private var isForegroundStarted = false

    private val logger: (LogType, String, String) -> Unit = { type, tag, message ->
        val priority = when (type) {
            LogType.VERBOSE -> Log.VERBOSE
            LogType.DEBUG -> Log.DEBUG
            LogType.INFO -> Log.INFO
            LogType.WARN -> Log.WARN
            LogType.ERROR -> Log.ERROR
        }
        if (priority >= Log.INFO || Log.isLoggable(LOG_TAG, priority)) {
            Log.println(priority, LOG_TAG, "[$tag] $message")
        }
        if (priority >= Log.INFO) bridge?.forwardLog(type, tag, message)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForegroundStarted) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            isForegroundStarted = true
        }
        if (intent?.action != ACTION_START) {
            if (bridge == null) stopSelf()
            return START_NOT_STICKY
        }
        if (bridge == null) startBridge(intent)
        return START_NOT_STICKY
    }

    private fun startBridge(intent: Intent) {
        val remoteAddress = try {
            InetAddress.getByName(intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: DEFAULT_REMOTE_ADDRESS)
        } catch (e: Exception) {
            logger(LogType.ERROR, "Service", "Invalid remote address: ${e.message}")
            stopSelf()
            return
        }
        val config = TBoxBridge.Config(
            localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, DEFAULT_LOCAL_PORT),
            remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, DEFAULT_REMOTE_PORT),
            remoteAddress = remoteAddress,
            legacyTcpPort = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT),
            v3TcpPort = intent.getIntExtra(EXTRA_V3_TCP_PORT, DEFAULT_V3_TCP_PORT),
            hostPackage = packageName,
            // adb shell setprop log.tag.TBoxBridge VERBOSE — лог каждого UDP-пакета
            logPackets = Log.isLoggable(LOG_TAG, Log.VERBOSE)
        )
        val created = TBoxBridge(config, logger, ::logStatus)

        val tracker = if (intent.getBooleanExtra(EXTRA_BIND_TO_TBOX_NETWORK, true)) {
            TBoxNetworkTracker(applicationContext, remoteAddress, logger) { network, iface ->
                created.onNetworkChanged(network?.let { n -> { socket -> n.bindSocket(socket) } }, iface)
            }
        } else {
            null
        }
        // Трекер стартует до моста: если сеть TBox уже есть, первый же сокет будет привязан.
        tracker?.start()

        when (created.start()) {
            TBoxBridge.StartResult.STARTED -> {
                bridge = created
                networkTracker = tracker
            }
            TBoxBridge.StartResult.UDP_BIND_FAILED,
            TBoxBridge.StartResult.TCP_BIND_FAILED -> {
                tracker?.stop()
                created.stop()
                stopSelf()
            }
        }
    }

    private fun logStatus(status: TBoxStatus) {
        Log.i(LOG_TAG, "Status ${status.type}: ${status.message}")
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
        networkTracker?.stop()
        networkTracker = null
        bridge?.stop()
        bridge = null
        isForegroundStarted = false
        super.onDestroy()
    }
}
