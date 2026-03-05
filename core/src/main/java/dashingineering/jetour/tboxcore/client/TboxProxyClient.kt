package dashingineering.jetour.tboxcore.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.ITboxHostService
import dashingineering.jetour.tboxcore.constants.TboxConstants
import dashingineering.jetour.tboxcore.service.TboxHostService
import kotlinx.coroutines.*
import kotlin.random.Random

class TboxProxyClient(
    private val context: Context,
    private val tBoxIp: String = TboxConstants.DEFAULT_TBOX_IP,
    private val tBoxPort: Int = TboxConstants.DEFAULT_TBOX_PORT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private var service: ITboxHostService? = null
    private var callback: ITboxHostCallback? = null
    private var isConnected = false
    private var isBound = false
    private var isBecomingHost = false

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

        override fun onServiceDisconnected(name: ComponentName?) {
            isConnected = false
            onEvent(Event.HostDied)
            scope.launch {
                delay(Random.nextLong(200, 800))
                becomeHostIfPossible()
            }
        }
    }

    fun connect() {
        // Ищем существующий хост
        val intent = Intent("dashingineering.jetour.tboxcore.HOST_SERVICE")
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveService(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveService(intent, 0)
        }

        if (resolveInfo != null) {
            // Хост найден — просто подключаемся
            callback?.onLogMessage("INFO", "HOST", "Хост AIDL найден ${resolveInfo.serviceInfo.packageName}:${resolveInfo.serviceInfo.name}")
            val componentName = ComponentName(
                resolveInfo.serviceInfo.packageName,
                resolveInfo.serviceInfo.name
            )
            val bindIntent = Intent().apply { component = componentName }
            if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                isBound = true
                return
            }
        }

        // Хоста нет — становимся хостом
        callback?.onLogMessage("INFO", "HOST", "Хост AIDL в системе не найден, создаем хост")
        becomeHost()
    }

    fun disconnect() {
        callback?.let { service?.unregisterCallback(it) }
        if (isBound) {
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // ignore
            }
            isBound = false
        }
        service = null
        isConnected = false
    }

    fun sendCommand(command: ByteArray): Boolean {
        return service?.sendCommand(command) ?: false
    }

    fun getHostPackageName(): String {
        return service?.getHostPackageName() ?: ""
    }

    private fun becomeHost() {
        if (isBecomingHost) return
        isBecomingHost = true
        scope.launch {
            try {
                delay(Random.nextLong(100, 500))

                // Запускаем встроенный сервис из библиотеки
                val intent = Intent(context, TboxHostService::class.java)
                ContextCompat.startForegroundService(context, intent)

                delay(800)

                // Подключаемся к себе
                val selfInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.resolveService(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.resolveService(intent, 0)
                }

                if (selfInfo != null) {
                    val componentName = ComponentName(
                        selfInfo.serviceInfo.packageName,
                        selfInfo.serviceInfo.name
                    )
                    val bindIntent = Intent().apply { component = componentName }
                    if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                        isBound = true
                        // ← Ключевой момент: инициируем подключение к TBox
                        service?.start(tBoxIp, tBoxPort)
                    } else {
                        onError("Failed to bind to self-host")
                    }
                } else {
                    onError("Self-host not found after launch")
                }
            } catch (e: Exception) {
                onError("Failed to become host: ${e.message}")
            } finally {
                isBecomingHost = false
            }
        }
    }

    private fun becomeHostIfPossible() {
        becomeHost()
    }

    init {
        callback = object : ITboxHostCallback.Stub() {
            override fun onDataReceived(data: ByteArray) {
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