package dashingineering.jetour.tboxcore.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.ITboxHostService
import dashingineering.jetour.tboxcore.service.TboxHostService
import kotlinx.coroutines.*
import kotlin.random.Random

class TboxProxyClient(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private var service: ITboxHostService? = null
    private var callback: ITboxHostCallback? = null
    private var isConnected = false

    var onEvent: (Event) -> Unit = {}
    var onError: (String) -> Unit = {}

    sealed class Event {
        object HostConnected : Event()
        object HostDisconnected : Event()
        data class DataReceived(val data: ByteArray) : Event()
        data class LogMessage(val level: String, val tag: String, val message: String) : Event()
        object HostDied : Event()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ITboxHostService.Stub.asInterface(binder)
            callback?.let { service?.registerCallback(it) }
            isConnected = true
            onEvent(Event.HostConnected)
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onServiceDisconnected(name: ComponentName?) {
            isConnected = false
            onEvent(Event.HostDied)
            scope.launch {
                delay(Random.nextLong(200, 800))
                becomeHostIfPossible()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun connect() {
        val intent = Intent("dashingineering.jetour.tboxcore.HOST_SERVICE")
        val resolveInfo = context.packageManager.resolveService(intent, 0)

        if (resolveInfo != null) {
            val componentName = ComponentName(
                resolveInfo.serviceInfo.packageName,
                resolveInfo.serviceInfo.name
            )
            val bindIntent = Intent().apply { component = componentName }
            if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                return
            }
        }

        becomeHost()
    }

    fun disconnect() {
        callback?.let { service?.unregisterCallback(it) }
        context.unbindService(connection)
        service = null
        isConnected = false
    }

    fun sendCommand(command: ByteArray): Boolean {
        return service?.sendCommand(command) ?: false
    }

    fun getHostPackageName(): String {
        return service?.getHostPackageName() ?: ""
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun becomeHost() {
        scope.launch {
            delay(Random.nextLong(100, 500))
            try {
                val intent = Intent(context, TboxHostService::class.java)
                ContextCompat.startForegroundService(context, intent)
                delay(800)
                connect()
            } catch (e: Exception) {
                onError("Failed to become host: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun becomeHostIfPossible() {
        becomeHost()
    }

    init {
        callback = object : ITboxHostCallback.Stub() {
            override fun onDataReceived( data: ByteArray) {
                scope.launch { onEvent(Event.DataReceived(data)) }
            }

            override fun onLogMessage(level: String, tag: String, message: String) {
                scope.launch { onEvent(Event.LogMessage(level, tag, message)) }
            }

            override fun onHostConnected() {
                scope.launch { onEvent(Event.HostConnected) }
            }

            override fun onHostDisconnected() {
                scope.launch { onEvent(Event.HostDisconnected) }
            }

            override fun onHostDied() {
                scope.launch { onEvent(Event.HostDied) }
            }
        }
    }
}