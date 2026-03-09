package dashingineering.jetour.tboxcore.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.*
import androidx.core.content.ContextCompat
import dashingineering.jetour.tboxcore.ITboxHostCallback
import dashingineering.jetour.tboxcore.ITboxHostService
import dashingineering.jetour.tboxcore.constants.TboxConstants
import dashingineering.jetour.tboxcore.service.TboxHostService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

private fun PackageManager.resolveServiceInfo(intent: Intent): ResolveInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveService(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        resolveService(intent, 0)
    }
}

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
    private val hostMutex = Mutex()
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
            // Инициируем подключение к TBox
            service?.start(tBoxIp, tBoxPort)
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
        scope.launch {
            hostMutex.withLock {
                // Ищем существующий хост
                val intent = Intent("dashingineering.jetour.tboxcore.HOST_SERVICE")
                val resolveInfo = context.packageManager.resolveServiceInfo(intent)

                if (resolveInfo != null) {
                    // Хост найден — просто подключаемся
                    callback?.onLogMessage("INFO", "HOST", "Хост AIDL найден: ${resolveInfo.serviceInfo.packageName}:${resolveInfo.serviceInfo.name}")
                    val componentName = ComponentName(
                        resolveInfo.serviceInfo.packageName,
                        resolveInfo.serviceInfo.name
                    )
                    val bindIntent = Intent().apply { component = componentName }
                    if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                        isBound = true
                        return@launch
                    }
                }

                // Хоста нет — становимся хостом
                callback?.onLogMessage("INFO", "HOST", "Хост AIDL в системе не найден, создаем хост")
                becomeHost()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            hostMutex.withLock {
                if (isBound) {
                    try {
                        context.unbindService(connection)
                        isBound = false
                    } catch (e: IllegalArgumentException) {
                        callback?.onLogMessage("ERROR", "HOST", "Не удалось отключиться от хоста, хост не найден")
                    }
                }
                callback?.onLogMessage("INFO", "HOST", "Disconnect")
                callback?.onHostDisconnected()
                service = null
                isConnected = false
                callback?.let { service?.unregisterCallback(it) }
            }
        }
    }

    fun sendCommand(command: ByteArray): Boolean {
        return service?.sendCommand(command) ?: false
    }

    fun getHostPackageName(): String {
        return service?.getHostPackageName() ?: ""
    }

    private suspend fun becomeHost() {
        hostMutex.withLock {
            if (isBecomingHost) return
            isBecomingHost = true
        }

        scope.launch {
            var attempts = 0
            val maxAttempts = 3

            while (attempts < maxAttempts) {
                try {
                    delay(Random.nextLong(100, 500))
                    attempts++

                    // Запускаем встроенный сервис из библиотеки
                    val intent = Intent(context, TboxHostService::class.java)
                    ContextCompat.startForegroundService(context, intent)

                    delay(800)

                    // Проверяем, существует ли уже хост (возможно, другой клиент стал хостом)
                    val existingHost = findExistingHost()
                    if (existingHost != null) {
                        // Другой клиент стал хостом — подключаемся к нему
                        callback?.onLogMessage("INFO", "HOST", "Другой хост найден: ${existingHost.packageName}, подключаемся")
                        bindToHost(existingHost)
                        return@launch
                    }

                    // Подключаемся к себе
                    val selfInfo = context.packageManager.resolveServiceInfo(intent)
                    if (selfInfo != null) {
                        val componentName = ComponentName(
                            selfInfo.serviceInfo.packageName,
                            selfInfo.serviceInfo.name
                        )
                        val bindIntent = Intent().apply { component = componentName }
                        if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
                            isBound = true
                            // Инициируем подключение к TBox
                            service?.start(tBoxIp, tBoxPort)
                            callback?.onLogMessage("INFO", "HOST", "Успешно стали хостом с попытки #$attempts")
                            return@launch
                        } else {
                            callback?.onLogMessage("WARN", "HOST", "Не удалось привязаться к сервису (попытка $attempts)")
                        }
                    } else {
                        callback?.onLogMessage("WARN", "HOST", "Сервис не найден после запуска (попытка $attempts)")
                    }
                } catch (e: Exception) {
                    callback?.onLogMessage("ERROR", "HOST", "Ошибка при становлении хостом (попытка $attempts): ${e.message}")
                }

                if (attempts < maxAttempts) {
                    delay(Random.nextLong(500, 1500))
                }
            }

            onError("Failed to become host after $maxAttempts attempts")
            hostMutex.withLock {
                isBecomingHost = false
            }
        }
    }

    private fun findExistingHost(): ComponentName? {
        val intent = Intent("dashingineering.jetour.tboxcore.HOST_SERVICE")
        val resolveInfo = context.packageManager.resolveServiceInfo(intent)
        return if (resolveInfo != null) {
            ComponentName(
                resolveInfo.serviceInfo.packageName,
                resolveInfo.serviceInfo.name
            )
        } else {
            null
        }
    }

    private fun bindToHost(componentName: ComponentName) {
        val bindIntent = Intent().apply { component = componentName }
        if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
            isBound = true
        }
    }

    private suspend fun becomeHostIfPossible() {
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