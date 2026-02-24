package dashingineering.jetour.tboxcore.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.ITboxHostService
import kotlinx.coroutines.*

class TboxProxyClient(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private var service: ITboxHostService? = null
    private var callback: ITboxHostCallback? = null
    private var isConnected = false
    private var hostPackageName: String? = null

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
            hostPackageName = service?.hostPackageName
            onEvent(Event.HostConnected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isConnected = false
            onEvent(Event.HostDied)
            // ← Здесь клиент может решить: "я стану хостом"
        }
    }

    fun connect(): Boolean {
        val intent = Intent().apply {
            setComponent(ComponentName(
                "dashingineering.jetour.tboxhost", // ← package хост-приложения
                "dashingineering.jetour.tboxhost.TboxHostService"
            ))
        }
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
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

    fun isRunning(): Boolean = service?.isRunning ?: false

    init {
        callback = object : ITboxHostCallback.Stub() {
            override fun onDataReceived( data: ByteArray) {
                scope.launch { onEvent(Event.DataReceived(data)) }
            }

            override fun onLogMessage(level: String, tag: String, message: String) {
                scope.launch { onEvent(Event.LogMessage(level, tag, message)) }
            }

            override fun onHostDied() {
                scope.launch { onEvent(Event.HostDied) }
            }

            override fun onHostConnected() {
                scope.launch { onEvent(Event.HostConnected) }
            }

            override fun onHostDisconnected() {
                scope.launch { onEvent(Event.HostDisconnected) }
            }
        }
    }
}