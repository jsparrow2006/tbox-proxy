package dashingineering.jetour.tboxcore.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import dashingineering.jetour.tboxcore.client.TboxBroadcastCoordinator
import dashingineering.jetour.tboxcore.client.TboxUdpHost
import dashingineering.jetour.tboxcore.util.buildTboxPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TBox хост сервис с поддержкой межприложенного взаимодействия через Broadcast
 * 
 * Используется вместе с TboxBroadcastCoordinator для координации между приложениями
 */
class TboxBroadcastHostService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpHost: TboxUdpHost? = null
    private val hostMutex = Mutex()
    private var coordinator: TboxBroadcastCoordinator? = null
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(50048, createNotification())
        
        // Инициализируем координатор
        coordinator = TboxBroadcastCoordinator(
            context = this,
            scope = scope,
            commandCallback = { data ->
                // При получении команды из broadcast отправляем в UDP
                runBlocking { udpHost?.sendCommand(data) } ?: false
            }
        )
        
        // Регистрируем слушателя координатора
        coordinator?.addListener(object : TboxBroadcastCoordinator.CoordinatorListener {
            override fun onDataReceived(data: ByteArray) {
                // Данные уже транслируются из hostListener
            }

            override fun onLog(level: String, tag: String, message: String) {
                android.util.Log.d("TBoxBroadcast[$tag]", message)
            }

            override fun onHostConnected() {
                android.util.Log.d("TBoxBroadcast", "Стали хостом")
            }

            override fun onHostDisconnected() {
                android.util.Log.d("TBoxBroadcast", "Остановили хост")
            }

            override fun onHostFound(packageName: String) {
                android.util.Log.d("TBoxBroadcast", "Нашли хост: $packageName")
            }

            override fun onNoHostFound() {
                android.util.Log.d("TBoxBroadcast", "Хост не найден, становимся хостом")
                becomeHost()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Этот сервис не использует AIDL, только Broadcast
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: DEFAULT_IP
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startHost(ip, port)
            }
            ACTION_STOP -> {
                stopHost()
            }
            ACTION_SEND_COMMAND -> {
                val data = intent.getByteArrayExtra(EXTRA_DATA)
                if (data != null) {
                    sendCommand(data)
                }
            }
        }
        return START_STICKY
    }

    private fun startHost(ip: String, port: Int) {
        scope.launch {
            hostMutex.withLock {
                if (isConnected) {
                    android.util.Log.d("TBoxBroadcast", "Хост уже запущен")
                    return@withLock
                }

                udpHost = TboxUdpHost(scope, ip, port)
                
                // Добавляем слушателя для трансляции данных в broadcast
                udpHost?.addListener(object : dashingineering.jetour.tboxcore.client.TboxHostListener {
                    override suspend fun onDataReceived(data: ByteArray) {
                        coordinator?.broadcastDataReceived(data)
                    }

                    override suspend fun onLog(level: String, tag: String, message: String) {
                        coordinator?.broadcastLog(level, tag, message)
                    }

                    override suspend fun onHostConnected() {
                        coordinator?.broadcastLog("INFO", "UDP", "Сокет создан: $ip:$port")
                    }

                    override suspend fun onHostDisconnected() {
                        coordinator?.broadcastLog("INFO", "UDP", "Сокет закрыт")
                    }
                })

                val success = udpHost?.start(ip, port) == true
                if (success) {
                    isConnected = true
                    // Становимся хостом в координаторе
                    coordinator?.becomeHost()
                }
            }
        }
    }

    private fun stopHost() {
        scope.launch {
            hostMutex.withLock {
                coordinator?.stopHost()
                udpHost?.stop()
                udpHost = null
                isConnected = false
            }
        }
    }

    private fun sendCommand(data: ByteArray) {
        scope.launch {
            hostMutex.withLock {
                udpHost?.sendCommand(data)
            }
        }
    }

    private fun becomeHost() {
        scope.launch {
            hostMutex.withLock {
                if (isConnected) {
                    android.util.Log.d("TBoxBroadcast", "Уже хост")
                    return@withLock
                }

                udpHost = TboxUdpHost(scope, DEFAULT_IP, DEFAULT_PORT)
                
                udpHost?.addListener(object : dashingineering.jetour.tboxcore.client.TboxHostListener {
                    override suspend fun onDataReceived(data: ByteArray) {
                        coordinator?.broadcastDataReceived(data)
                    }

                    override suspend fun onLog(level: String, tag: String, message: String) {
                        coordinator?.broadcastLog(level, tag, message)
                    }

                    override suspend fun onHostConnected() {
                        coordinator?.broadcastLog("INFO", "UDP", "Сокет создан: $DEFAULT_IP:$DEFAULT_PORT")
                    }

                    override suspend fun onHostDisconnected() {
                        coordinator?.broadcastLog("INFO", "UDP", "Сокет закрыт")
                    }
                })

                val success = udpHost?.start(DEFAULT_IP, DEFAULT_PORT) == true
                if (success) {
                    isConnected = true
                    coordinator?.becomeHost()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.launch {
            hostMutex.withLock {
                coordinator?.destroy()
                coordinator = null
                udpHost?.stop()
                udpHost = null
                isConnected = false
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tbox_broadcast_host_channel",
                "TBox Broadcast Host",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "tbox_broadcast_host_channel")
            .setContentTitle("TBox Broadcast Host")
            .setContentText("Managing TBox UDP connection with Broadcast")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "dashingineering.jetour.tboxcore.START"
        const val ACTION_STOP = "dashingineering.jetour.tboxcore.STOP"
        const val ACTION_SEND_COMMAND = "dashingineering.jetour.tboxcore.SEND_COMMAND"
        
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_DATA = "data"
        
        const val DEFAULT_IP = "192.168.225.1"
        const val DEFAULT_PORT = 50047
    }
}

// Extension для получения ByteArray из Intent
private fun Intent.getByteArrayExtra(key: String): ByteArray? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getByteArrayExtra(key)
    } else {
        @Suppress("DEPRECATION")
        getByteArrayExtra(key)
    }
}
