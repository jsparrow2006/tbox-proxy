package dashingineering.jetour.tboxcore.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.ITboxHostService
import dashingineering.jetour.tboxcore.client.TboxBroadcastCoordinator
import dashingineering.jetour.tboxcore.client.TboxHostListener
import dashingineering.jetour.tboxcore.client.TboxUdpHost
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TboxHostService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpHost: TboxUdpHost? = null
    private val hostMutex = Mutex()
    private val callbackList = RemoteCallbackList<ITboxHostCallback>()
    private var coordinator: TboxBroadcastCoordinator? = null

    private val hostListener = object : TboxHostListener {
        override suspend fun onDataReceived(data: ByteArray) {
            notifyCallbacks { onDataReceived(data) }
            // Транслируем данные через broadcast другим приложениям
            coordinator?.broadcastDataReceived(data)
        }

        override suspend fun onLog(level: String, tag: String, message: String) {
            notifyCallbacks { onLogMessage(level, tag, message) }
            // Транслируем лог через broadcast
            coordinator?.broadcastLog(level, tag, message)
        }

        override suspend fun onHostConnected() {
            notifyCallbacks { onHostConnected() }
            coordinator?.broadcastLog("INFO", "HOST", "Хост подключён")
        }

        override suspend fun onHostDisconnected() {
            notifyCallbacks { onHostDisconnected() }
            coordinator?.broadcastLog("INFO", "HOST", "Хост отключён")
        }
    }

    private suspend fun notifyCallbacks(block: suspend ITboxHostCallback.() -> Unit) {
        val n = callbackList.beginBroadcast()
        repeat(n) {
            try {
                val callback = callbackList.getBroadcastItem(it)
                // Выносим AIDL вызов в IO dispatcher с таймаутом для избежания блокировки
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(2000) {
                        block(callback)
                    }
                }
            } catch (e: RemoteException) {
                // Dead callback — будет удалён при finishBroadcast
            } catch (e: TimeoutCancellationException) {
                // Callback слишком медленный — пропускаем
            }
        }
        callbackList.finishBroadcast()
    }

    private val binder = object : ITboxHostService.Stub() {
        override fun registerCallback(cb: ITboxHostCallback) {
            callbackList.register(cb)
        }

        override fun unregisterCallback(cb: ITboxHostCallback) {
            callbackList.unregister(cb)
        }

        override fun start(ip: String, port: Int): Boolean {
            android.util.Log.d("TBoxHostService", "start() called with ip=$ip, port=$port")
            return runBlocking {
                hostMutex.withLock {
                    if (udpHost == null) {
                        android.util.Log.d("TBoxHostService", "Creating new TboxUdpHost")
                        udpHost = TboxUdpHost(scope)
                        udpHost!!.addListener(hostListener)
                    }
                    android.util.Log.d("TBoxHostService", "Starting UDP host...")
                    val result = udpHost!!.start(ip, port)
                    android.util.Log.d("TBoxHostService", "UDP host start result: $result")
                    result
                }
            }
        }

        override fun stop() {
            runBlocking {
                hostMutex.withLock {
                    udpHost?.stop()
                    udpHost = null
                }
            }
        }

        override fun sendCommand(cmd: ByteArray): Boolean {
            return runBlocking {
                hostMutex.withLock {
                    udpHost?.sendCommand(cmd) ?: false
                }
            }
        }

        override fun isRunning(): Boolean {
            return runBlocking {
                hostMutex.withLock {
                    udpHost?.isRunning ?: false
                }
            }
        }

        override fun getHostPackageName(): String = packageName
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(50047, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tbox_host_channel",
                "TBox Host",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "tbox_host_channel")
            .setContentTitle("TBox Host")
            .setContentText("Managing TBox UDP connection")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // Сначала останавливаем UDP хост синхронно, чтобы избежать утечки сокета
        runBlocking {
            hostMutex.withLock {
                udpHost?.stop()
                udpHost = null
            }
        }
        // Затем отменяем scope
        scope.cancel()
        callbackList.kill()
        super.onDestroy()
    }
}