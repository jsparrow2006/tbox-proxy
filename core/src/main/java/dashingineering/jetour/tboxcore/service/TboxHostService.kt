package dashingineering.jetour.tboxcore.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.ITboxHostService
import dashingineering.jetour.tboxcore.client.TboxHostListener
import dashingineering.jetour.tboxcore.client.TboxUdpHost
import kotlinx.coroutines.*

class TboxHostService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpHost: TboxUdpHost? = null
    private val callbackList = RemoteCallbackList<ITboxHostCallback>()

    private val hostListener = object : TboxHostListener {
        override suspend fun onDataReceived(data: ByteArray) {
            notifyCallbacks { onDataReceived(data) }
        }

        override suspend fun onLog(level: String, tag: String, message: String) {
            notifyCallbacks { onLogMessage(level, tag, message) }
        }

        override suspend fun onHostConnected() {
            notifyCallbacks { onHostConnected() }
        }

        override suspend fun onHostDisconnected() {
            notifyCallbacks { onHostDisconnected() }
        }
    }

    private suspend fun notifyCallbacks(block: suspend ITboxHostCallback.() -> Unit) {
        val n = callbackList.beginBroadcast()
        repeat(n) {
            try {
                block(callbackList.getBroadcastItem(it))
            } catch (e: RemoteException) { /* ignore dead */ }
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
            return runBlocking {
                if (udpHost == null) {
                    udpHost = TboxUdpHost(scope)
                    udpHost!!.addListener(hostListener)
                }
                udpHost!!.start(ip, port)
            }
        }

        override fun stop() {
            runBlocking {
                udpHost?.stop()
                udpHost = null
            }
        }

        override fun sendCommand(cmd: ByteArray): Boolean {
            return runBlocking {
                udpHost?.sendCommand(cmd) ?: false
            }
        }

        override fun isRunning(): Boolean {
            return runBlocking {
                udpHost?.isRunning ?: false
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
        scope.launch {
            udpHost?.stop()
            udpHost = null
        }
        scope.cancel()
        super.onDestroy()
    }
}